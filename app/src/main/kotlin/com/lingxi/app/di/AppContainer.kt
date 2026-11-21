package com.lingxi.app.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.lingxi.app.BuildConfig
import com.lingxi.app.pipeline.CapturePipeline
import com.lingxi.app.pipeline.CaptureRunner
import com.lingxi.app.pipeline.OrganizeWorker
import com.lingxi.core.ai.DefaultRoutingPolicy
import com.lingxi.core.ai.DeviceState
import com.lingxi.core.ai.DeviceStateProvider
import com.lingxi.core.ai.InferenceRouter
import com.lingxi.core.ai.NetworkType
import com.lingxi.core.ai.PrivacySettings
import com.lingxi.core.ai.toExecution
import com.lingxi.core.data.RoomAiExecutionRepository
import com.lingxi.core.data.RoomCaptureRepository
import com.lingxi.core.data.RoomCardRepository
import com.lingxi.core.data.RoomCourseRepository
import com.lingxi.core.data.RoomKnowledgeRepository
import com.lingxi.core.data.RoomNoteRepository
import com.lingxi.core.data.RoomPendingTaskRepository
import com.lingxi.core.data.RoomPushRepository
import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.model.repository.AiExecutionRepository
import com.lingxi.core.model.repository.CaptureRepository
import com.lingxi.core.model.repository.CardRepository
import com.lingxi.core.model.repository.CourseRepository
import com.lingxi.core.model.repository.KnowledgeRepository
import com.lingxi.core.model.repository.NoteRepository
import com.lingxi.core.model.repository.PendingTaskRepository
import com.lingxi.core.model.repository.PushRepository
import com.lingxi.core.network.GatewayApi
import com.lingxi.core.network.OkHttpGatewayApi
import com.lingxi.core.ondevice.PaddleOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 手写依赖注入容器（ADR 0004：不引入 Hilt）。进程内唯一，由 [com.lingxi.app.LingxiApplication] 创建。
 *
 * 界面层只拿到仓库接口（`core:model`），不直接接触 Room 或 OkHttp。
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LingxiDatabase by lazy { LingxiDatabase.open(appContext) }
    val settings = AppSettings(appContext)

    val cards: CardRepository by lazy { RoomCardRepository(database) }
    val courses: CourseRepository by lazy { RoomCourseRepository(database) }
    val knowledge: KnowledgeRepository by lazy { RoomKnowledgeRepository(database) }
    val aiExecutions: AiExecutionRepository by lazy { RoomAiExecutionRepository(database) }
    val pushes: PushRepository by lazy { RoomPushRepository(database) }
    val captures: CaptureRepository by lazy { RoomCaptureRepository(database) }
    val notes: NoteRepository by lazy { RoomNoteRepository(database) }
    val pendingTasks: PendingTaskRepository by lazy { RoomPendingTaskRepository(database) }

    /** 端侧 OCR（PP-OCRv4 + ONNX Runtime），首次识别时才加载模型。 */
    val ocr by lazy { PaddleOcrEngine(appContext) }

    /** 捕获页当前选择的课程（上课时间内自动预选正在上的课，FR-COURSE-03）。 */
    val selectedCourseId = MutableStateFlow<String?>(null)

    /** 在应用级协程里依次处理新照片。 */
    val captureRunner: CaptureRunner by lazy { CaptureRunner(scope, pipeline) }

    /** 拍板书到卡片的流水线 [FR-NOTE-03]。 */
    val pipeline: CapturePipeline by lazy {
        CapturePipeline(
            context = appContext,
            captures = captures,
            courses = courses,
            knowledge = knowledge,
            cards = cards,
            notes = notes,
            pending = pendingTasks,
            executions = aiExecutions,
            ocr = ocr,
            gateway = ::gateway,
            policy = DefaultRoutingPolicy(),
            deviceState = deviceState,
            scheduleRetry = { OrganizeWorker.schedule(appContext) },
        )
    }

    private val gatewayLock = Mutex()
    private var gatewayInstance: GatewayApi? = null

    /** 网关客户端；设备 id 存在 DataStore 中，首次使用时读取。 */
    suspend fun gateway(): GatewayApi =
        gatewayLock.withLock {
            gatewayInstance ?: OkHttpGatewayApi(BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_TOKEN, settings.deviceId())
                .also { gatewayInstance = it }
        }

    @Volatile private var privacy = PrivacySettings()

    init {
        settings.privacy.onEach { privacy = it }.launchIn(scope)
    }

    /** 路由决策用的实时设备状态：网络、电量、发热与隐私设置。 */
    val deviceState = DeviceStateProvider { readDeviceState() }

    /** 端云协同推理入口；每次调用写入 `ai_execution` [FR-AI-04]。端侧后端在 I3 接入 MNN 后注册。 */
    val router: InferenceRouter by lazy {
        InferenceRouter(
            policy = DefaultRoutingPolicy(),
            backends = listOf(GatewayBackend(::gateway)),
            deviceState = deviceState,
            observer = { result, start -> aiExecutions.record(result.toExecution(start)) },
        )
    }

    private fun readDeviceState(): DeviceState {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network =
            when {
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkType.UNMETERED
                else -> NetworkType.METERED
            }
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        val charging = battery?.isCharging ?: false
        val power = appContext.getSystemService(PowerManager::class.java)
        // 热状态 API 需要 Android 10，更早的系统视为未降频。
        val hot =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_SEVERE
        return DeviceState(network, percent, charging, hot, privacy.allowCloudFor())
    }
}
