#!/usr/bin/env bash
# 启动本项目的 Android 模拟器。
#
# 为什么需要这个脚本：本机终端开着代理（http_proxy 等变量）时，模拟器会把它传给内部的网络组件 netsimd，
# netsimd 会把模拟器里的所有 TCP 连接都转发给代理，结果手机模拟器连不上 Mac 本机的网关（10.0.2.2）；
# 代理变量还可能让模拟器连接 netsimd 的 gRPC 请求被发往代理而卡死。
# 所以这里去掉代理变量再启动，模拟器直连网络。需要让模拟器走代理时，在模拟器的「设置 > 代理」里单独配置。
# 另外，模拟器崩溃过一次后，下次启动会弹窗询问是否上传崩溃报告，后台启动时这个弹窗会让它一直卡住，
# 所以固定关闭崩溃上报（-crash-report-mode never -no-metrics），也避免把本机数据发给第三方。
#
# 用法：scripts/emulator.sh [watch|phone|<AVD 名称>] [额外的 emulator 参数...]
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
case "${1:-watch}" in
  watch) AVD=lingxi_watch ;;
  phone) AVD=lingxi_phone ;;
  *) AVD="$1" ;;
esac
shift || true

unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
export no_proxy="localhost,127.0.0.1,::1"
exec "$SDK/emulator/emulator" -avd "$AVD" -no-boot-anim -crash-report-mode never -no-metrics "$@"
