# Исправление: Вылет приложения при подключении к камерам без HTTP доступа

## Проблема
Приложение вылетало при попытке подключения к камерам, у которых доступны только RTSP и PTZ порты, но HTTP порт (80) недоступен.

## Причина
В классе `CameraHttp.kt` метод `send()` выбрасывал необработанные исключения при:
- Недоступном HTTP порте (`ConnectException`)
- Превышении времени ожидания (`SocketTimeoutException`)
- Неизвестном хосте (`UnknownHostException`)

Эти исключения не перехватывались в методах `request()` и `requestBytes()`, что приводило к крашу приложения.

## Решение

### 1. Улучшена обработка исключений в `CameraHttp.send()`
```kotlin
private fun send(...): RawResponse {
    var connection: HttpURLConnection? = null
    try {
        // ... код подключения ...
    } catch (e: java.net.ConnectException) {
        throw java.io.IOException("HTTP порт недоступен: ${e.message}", e)
    } catch (e: java.net.SocketTimeoutException) {
        throw java.io.IOException("Превышено время ожидания HTTP: ${e.message}", e)
    } catch (e: java.net.UnknownHostException) {
        throw java.io.IOException("Неизвестный хост: ${e.message}", e)
    } catch (e: Exception) {
        throw java.io.IOException("Ошибка HTTP запроса: ${e.message}", e)
    } finally {
        connection?.disconnect()
    }
}
```

### 2. Добавлена обработка IOException в `request()`
```kotlin
fun request(...): Response {
    try {
        // ... логика запроса ...
    } catch (e: java.io.IOException) {
        // Возвращаем ошибку как специальный код (0 = сетевая ошибка)
        return Response(0, e.message ?: "Network error", authChallenged = false)
    }
}
```

### 3. Добавлена обработка IOException в `requestBytes()`
```kotlin
fun requestBytes(...): ByteArray? {
    return try {
        // ... логика запроса ...
    } catch (e: java.io.IOException) {
        // При ошибке сети возвращаем null
        null
    }
}
```

## Результат
- ✅ Приложение больше не вылетает при подключении к камерам без HTTP доступа
- ✅ Методы корректно возвращают ошибку (код 0 или null) вместо краша
- ✅ Все существующие вызовы `CameraHttp` уже обернуты в try-catch блоки и обрабатывают эти ситуации
- ✅ HTTP порт теперь опциональный - камеры могут работать только с RTSP/PTZ

## Затронутые компоненты
- `CameraHttp.kt` - основной HTTP клиент
- `PtzController.kt` - PTZ команды через HTTP (уже имел обработку)
- `OnvifClient.kt` - ONVIF SOAP запросы (уже имел обработку)
- `NetworkTester.kt` - проверка авторизации (уже имел обработку)
- `PtzProtocolDetector.kt` - автоопределение протокола (уже имел обработку)
- `CameraFrameGrabber.kt` - захват кадров (уже имел обработку)

## Тестирование
Для проверки исправления:
1. Добавьте камеру с корректными RTSP и PTZ портами, но недоступным HTTP портом
2. Попробуйте подключиться к камере
3. Приложение должно работать без краша, используя только RTSP для видео
4. PTZ функции могут не работать если требуют HTTP, но это не вызовет краш

## Дата исправления
2026-10-05
