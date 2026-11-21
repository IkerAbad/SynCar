# SynCar

App Android en Kotlin con Jetpack Compose (mayo de 2026) que se conecta por Bluetooth de bajo consumo a un dispositivo llamado «SynCar», muestra en tiempo real la temperatura, la humedad y la distancia que mide, y guarda el historial de cada trayecto junto con su ruta GPS.

## Qué hay

- Conexión BLE (`BleManager.kt`): busca un dispositivo con el nombre `SynCar` y recibe sus datos por notificaciones GATT, en mensajes de texto con la forma `TEMP:valor`, `HUM:valor` y `DIST:valor`.
- Panel en tiempo real con tarjetas de estado, gráficas (MPAndroidChart) y registro de lo recibido.
- Alertas: temperatura crítica por encima de 38 °C y colisión inminente por debajo de 15 cm, entre otras.
- Trayectos: se inicia y se cierra un trayecto, se guardan los datos y la posición, y luego se consultan en un historial con estadísticas y la ruta dibujada en Google Maps.
- Usuarios locales en SQLite (`DatabaseHelper.kt`, `SynCarRepository.kt`): la primera vez se crea un administrador, y hay pantalla de gestión de usuarios. Las contraseñas se guardan con hash (PBKDF2 con sal); las que se guardaron antes sin cifrar se convierten en el primer acceso correcto.
- Arquitectura sencilla con `ViewModel` y repositorio.
- El dispositivo que emite los datos no está en este repositorio.

No he podido compilarla ni ejecutarla aquí: no tengo el SDK de Android en mi entorno de revisión. En particular, el cambio de las contraseñas a hash no está probado.

## Cómo probarla

La clave de Google Maps no está en el repositorio. Pon la tuya en `app/src/main/AndroidManifest.xml`, en el valor `COLOQUE_SU_GOOGLE_MAPS_API_AQUI`. Después abre el proyecto en Android Studio y ejecútalo en un móvil con Bluetooth, ya que el emulador no sirve para BLE. Necesita Android 7.0 (API 24) o superior.

## Licencia

MIT, ver [LICENSE](LICENSE).

---

# SynCar (English)

An Android app in Kotlin with Jetpack Compose (May 2026) that connects over Bluetooth Low Energy to a device named "SynCar", shows the temperature, humidity and distance it measures in real time, and stores the history of each trip along with its GPS route.

## What's inside

- BLE connection (`BleManager.kt`): looks for a device named `SynCar` and receives its data through GATT notifications, as text messages shaped like `TEMP:value`, `HUM:value` and `DIST:value`.
- A live dashboard with status cards, charts (MPAndroidChart) and a log of what was received.
- Alerts: critical temperature above 38 °C and imminent collision below 15 cm, among others.
- Trips: a trip is started and closed, data and position are stored, and they can later be browsed in a history with statistics and the route drawn on Google Maps.
- Local users in SQLite (`DatabaseHelper.kt`, `SynCarRepository.kt`): an administrator is created the first time, and there is a user management screen. Passwords are stored hashed (PBKDF2 with salt); ones saved earlier in plain text are converted on the first successful login.
- A simple architecture with a `ViewModel` and a repository.
- The device that sends the data is not in this repository.

I could not compile or run it here: I have no Android SDK in my review environment. In particular, the change to hashed passwords is untested.

## Trying it

The Google Maps key is not in the repository. Put yours in `app/src/main/AndroidManifest.xml`, in the value `COLOQUE_SU_GOOGLE_MAPS_API_AQUI`. Then open the project in Android Studio and run it on a phone with Bluetooth, since the emulator does not work for BLE. It needs Android 7.0 (API 24) or higher.

## License

MIT, see [LICENSE](LICENSE).
