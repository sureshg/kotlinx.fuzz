rootProject.name = "kotlinx.fuzz"
include("kotlinx.fuzz.api")
include("kotlinx.fuzz.engine")
include("kotlinx.fuzz.jazzer")
include("kotlinx.fuzz.gradle")
include("kotlinx.fuzz.junit")
includeBuild("kotlinx.fuzz.test")

// examples
includeBuild("kotlinx.fuzz.examples")
