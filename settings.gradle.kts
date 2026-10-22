pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 端末内・多言語の音声認識 (sherpa-onnx) は GitHub のリリースから AAR を直接取得する
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpaOnnxReleases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "AutoJimaku"
include(":app")
