// Compile-only stub'ы OEM-классов прошивки (android.car.* Geely/ECARX). В APK не попадают.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
