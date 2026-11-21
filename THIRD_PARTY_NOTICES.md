# Third-Party Notices (`THIRD_PARTY_NOTICES.md`)

ExtBridge (`com.extbridge.helper` & `com.extbridge.daemon.ExtBridge`) is licensed under the [MIT License](LICENSE) and bundles or depends on the following open-source components.

---

## 1. Kotlin / AndroidX / Compose / Miuix / Shizuku Libraries

1. **AndroidX Activity Compose (`androidx.activity:activity-compose:1.13.0`)**
   - Copyright (C) The Android Open Source Project
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
2. **JetBrains Compose Multiplatform (`org.jetbrains.compose.foundation` / `ui` `1.12.0`)**
   - Copyright (C) JetBrains s.r.o. and Compose Multiplatform Contributors, Google LLC
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
3. **Miuix KMP (`top.yukonga.miuix.kmp:miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-squircle` `0.9.4`)**
   - Copyright (C) YuKongA and Miuix Contributors (<https://github.com/miuix-kotlin-multiplatform/miuix>)
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
4. **Shizuku API (`dev.rikka.shizuku:api` / `aidl` / `provider` `13.1.5`)**
   - Copyright (C) RikkaW and Shizuku Contributors (<https://github.com/RikkaApps/Shizuku-API>)
   - License: [MIT License](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)
   - ExtBridge only uses the public client-side API (`Shizuku` helpers + the `IShizukuService#newProcess` binder transaction) to run its daemon bootstrap with the privilege the user already granted to Shizuku; the Shizuku manager app itself is **not** bundled.
