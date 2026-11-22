plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { implementation("com.google.zxing:core:3.5.3"); implementation("com.google.code.gson:gson:2.14.0"); testImplementation("junit:junit:4.13.2") }
