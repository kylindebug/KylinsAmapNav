# Third-party notices

The MIT license in LICENSE applies to this project's original application source and vector artwork, not to third-party dependencies, APIs, map data, or trademarks.

| Dependency | Use | License / terms |
| --- | --- | --- |
| AMap navi-3dmap 9.8.2_3dmap9.8.2 | Navigation, location and route geometry | Proprietary AMap SDK license and service terms; Maven POM identifies AMap Software License 1.0 |
| Google Play services Wearable 18.2.0 and transitive libraries | Paired-device Data Layer | Google Play services terms and dependency notices |
| AndroidX Wear 1.3.0, Wear Ongoing 1.0.0, Wear Remote Interactions 1.0.0, Fragment 1.6.2 and transitive AndroidX libraries | Ambient lifecycle, notification, remote activity launch, Activity support | Apache License 2.0; see individual artifacts |
| Kotlin 1.9.24 | Language runtime and build plugin | Apache License 2.0 |
| Android Gradle Plugin 8.5.2 | Build only | Apache License 2.0 |
| Gradle Wrapper 8.11.1 | Generated standard build bootstrap | Apache License 2.0 |
| JUnit 4.13.2 | Tests only | Eclipse Public License 1.0 |
| org.json 20240303 | JVM tests only | Public domain declaration in upstream JSON-java |

Official AMap Maven coordinates and license metadata:
https://repo.maven.apache.org/maven2/com/amap/api/navi-3dmap/9.8.2_3dmap9.8.2/navi-3dmap-9.8.2_3dmap9.8.2.pom

This source archive does not bundle AMap SDK binaries or signing private keys. The Gradle Wrapper JAR is the standard generated Gradle bootstrap. Building an APK downloads dependencies, and the APK necessarily includes third-party runtime code. End users must be shown the AMap privacy disclosure before the navigation SDK is initialized. SDK use, distribution, credentials and service quotas remain subject to their providers' terms.
