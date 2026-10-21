BOOGEYMAN (com.boogey.man) - Developer: Sham Shyam
Build steps (no server needed: both phones on the same Wi-Fi or one phone hotspot)
1. Server (a PC on the same Wi-Fi, or any public server):
   cd server && npm install && node server.js
2. Open this folder in Android Studio, let Gradle sync, press Run (or Build > Build APK). Install on both phones.
3. App: enter server address, e.g. ws://192.168.1.5:8080 (your PC's local IP).
4. Phone A (shared): "Allow remote control on THIS phone" -> turn on BOOGEYMAN in Accessibility. Then "Share my screen" -> QR appears.
5. Phone B: "Scan QR and view / control". Tap/swipe on the video; Back/Home/Recents buttons below.
Over the internet: host server publicly (wss://) and add a TURN server in Rtc.kt.
