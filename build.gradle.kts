plugins {
    id("com.android.application") version "8.13.0" apply false
    // 端末内AI (LiteRT-LM) が Kotlin 2.4 で作られているため、それを読める 2.3 系にする
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
