
private fun isValidIpv4(ip: String): Boolean {
    val value = ip.trim()

    if (value.contains("/")) {
        val parts = value.split("/")
        if (parts.size != 2) return false

        val address = parts[0]
        val prefix = parts[1].toIntOrNull() ?: return false

        if (prefix !in 20..32) return false

        return isPlainIpv4(address)
    }

    return isPlainIpv4(value)
}

private fun isPlainIpv4(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false

    return parts.all {
        val number = it.toIntOrNull()
        number != null && number in 0..255 && it.isNotEmpty()
    }
}
