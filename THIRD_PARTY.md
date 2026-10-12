# Third-party components

- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` are the standard Gradle wrapper, reused from the owner's existing DP-Launcher project, not original work. Gradle is Apache-2.0 licensed: https://github.com/gradle/gradle/blob/v8.13.0/LICENSE . Wrapper scripts retain upstream notices.
- Android platform MediaCodec / MediaMuxer / MediaExtractor provide AAC coding and MP4 muxing/extraction. This project does not implement an AAC compression algorithm.
- AGP 8.7.3, Gradle 8.13, NDK 28.2.13676358 and CMake 3.22.1 are build-time dependencies. Android SDK/system images and compiler binaries are not bundled.
- AOSP sources are linked as reference material, not copied into the project. Parser/application/tests were generated with AI assistance for this personal engineering practice; no past employment or independent mastery is implied.
