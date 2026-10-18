package kc.ac.uc.speechbridge

interface Platform {
    val name: String
    val kind: String
}

expect fun getPlatform(): Platform