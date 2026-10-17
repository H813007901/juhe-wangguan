package dev.aigw.core.gateway

/** 默认用网络接口枚举局域网地址；Android 侧注入系统实现更准。 */
object DefaultLanAddressProvider : LanAddressProvider {
    override fun lanAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
