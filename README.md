# music-widget-android
Android music widget — AppWidgetProvider + NotificationListenerService

Widget de música nativo, liviano y pensado para equipos viejos.

## v1.8
- Shuffle / Repeat (vía MediaController.TransportControls)
- Nombre del álbum en el subtítulo
- Barra de progreso en modo snapshot (sin timers, cero drenaje)
- Texto adaptado al layout chico (11sp, 2 líneas)
- Album art 256px con esquinas redondeadas, procesado fuera del hilo principal
- Diseño Material 3 Expressive: esquinas 28dp, colores dinámicos, targets táctiles grandes
- Sin polling: solo eventos de MediaSession/notificaciones
