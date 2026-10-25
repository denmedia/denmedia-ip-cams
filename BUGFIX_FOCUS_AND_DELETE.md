# Исправления: Фокус камеры и удаление камеры

## Дата: 2026-10-05

## Проблемы

### 1. Автоматический возврат фокуса
**Описание**: После изменения фокуса с помощью ползунка камера сначала меняла фокус, но сразу после автоматически возвращала фокус обратно в исходное положение.

**Причина**: В коде `PtzController.kt` при неудачной попытке переместить фокус камера автоматически переключалась в режим MANUAL. Однако после этого код продолжал выполнение без задержки (`continue` без `delay`), что приводило к конфликту команд и возврату фокуса.

### 2. Медленное изменение фокуса
**Описание**: Ползунок фокуса слишком медленно изменял фокус камеры, что делало управление неудобным.

**Причина**: Константы для пошагового фокуса были установлены слишком консервативно:
- `RELATIVE_FOCUS_STEP = 0.04f` (слишком маленький шаг)
- `FOCUS_STEP_INTERVAL_MS = 250L` (слишком большой интервал между шагами)

### 3. Отсутствие кнопки удаления камеры
**Описание**: В форме редактирования камеры не было кнопки для удаления камеры.

## Решения

### 1. Исправление автоматического возврата фокуса

**Файл**: `app/src/main/java/com/example/denmediaipcams/network/PtzController.kt`

**Изменения**:
```kotlin
// Было:
if (!moved && !manualModeTried) {
    manualModeTried = true
    setOnvifFocusMode(camera, target, "MANUAL")
    continue  // ❌ Продолжает сразу без задержки
}

// Стало:
if (!moved && !manualModeTried) {
    manualModeTried = true
    setOnvifFocusMode(camera, target, "MANUAL")
    // Ждем интервал чтобы камера применила изменения
    delay(FOCUS_STEP_INTERVAL_MS)
    continue  // ✅ Теперь с задержкой
}
```

**Результат**: Камера корректно переключается в ручной режим фокусировки и применяет изменения перед следующей командой.

### 2. Ускорение изменения фокуса

**Файл**: `app/src/main/java/com/example/denmediaipcams/network/PtzController.kt`

**Изменения**:
```kotlin
// Было:
private const val RELATIVE_FOCUS_STEP = 0.04f  // Весь диапазон за ~12 секунд
private const val FOCUS_STEP_INTERVAL_MS = 250L

// Стало:
private const val RELATIVE_FOCUS_STEP = 0.10f  // Весь диапазон за ~5-6 секунд
private const val FOCUS_STEP_INTERVAL_MS = 150L
```

**Результат**: 
- Шаг фокуса увеличен в 2.5 раза (0.04 → 0.10)
- Интервал между шагами уменьшен на 40% (250мс → 150мс)
- Общая скорость изменения фокуса увеличена примерно в 4 раза

### 3. Добавление кнопки удаления камеры

**Файлы**:
- `app/src/main/java/com/example/denmediaipcams/ui/components/CameraEditDialog.kt`
- `app/src/main/java/com/example/denmediaipcams/MainActivity.kt`

**Изменения**:

1. Добавлен параметр `onDelete` в `CameraEditDialog`:
```kotlin
fun CameraEditDialog(
    cameraToEdit: IpCamera? = null,
    draft: IpCamera? = null,
    onDismiss: () -> Unit,
    onSave: (IpCamera) -> Unit,
    onDelete: ((String) -> Unit)? = null  // ✅ Новый параметр
)
```

2. Добавлена кнопка удаления в диалоге (показывается только при редактировании):
```kotlin
dismissButton = {
    Row {
        // Кнопка удаления камеры (только при редактировании)
        if (isEditing && onDelete != null && cameraToEdit != null) {
            TextButton(
                onClick = { showDeleteConfirmation = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, ...)
                Text("Удалить")
            }
        }
        TextButton(onClick = onDismiss) {
            Text("Отмена")
        }
    }
}
```

3. Добавлен диалог подтверждения удаления:
```kotlin
if (showDeleteConfirmation && cameraToEdit != null) {
    AlertDialog(
        title = { Text("Удалить камеру?") },
        text = { Text("Камера \"${cameraToEdit.name}\" будет удалена. Это действие необратимо.") },
        confirmButton = {
            TextButton(onClick = {
                onDelete?.invoke(cameraToEdit.id)
                showDeleteConfirmation = false
                onDismiss()
            }) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = { showDeleteConfirmation = false }) {
                Text("Отмена")
            }
        }
    )
}
```

4. Подключена функция удаления в `MainActivity.kt`:
```kotlin
CameraEditDialog(
    cameraToEdit = cameraToEdit,
    draft = cameraDraft,
    onDismiss = { isAddOrEditDialogShown = false },
    onSave = { camera -> ... },
    onDelete = { cameraId ->  // ✅ Новый обработчик
        viewModel.deleteCamera(cameraId)
        isAddOrEditDialogShown = false
    }
)
```

**Результат**: 
- ✅ Кнопка "Удалить" появляется только при редактировании существующей камеры
- ✅ При нажатии показывается диалог подтверждения с названием камеры
- ✅ Кнопка удаления красного цвета для предупреждения
- ✅ Камера удаляется только после подтверждения

## Тестирование

### Тест 1: Проверка фокуса
1. Откройте камеру с поддержкой PTZ
2. Перейдите к управлению фокусом
3. Переместите ползунок фокуса влево или вправо
4. **Ожидаемый результат**: Фокус плавно изменяется и остается в новом положении без возврата обратно

### Тест 2: Скорость фокуса
1. Откройте камеру с поддержкой PTZ
2. Переместите ползунок фокуса на максимум
3. **Ожидаемый результат**: Фокус изменяется примерно в 4 раза быстрее, чем раньше (~5-6 секунд для полного диапазона вместо ~12 секунд)

### Тест 3: Удаление камеры
1. Откройте существующую камеру для редактирования
2. **Ожидаемый результат**: В нижней части диалога появляется красная кнопка "Удалить"
3. Нажмите кнопку "Удалить"
4. **Ожидаемый результат**: Появляется диалог подтверждения с названием камеры
5. Нажмите "Отмена"
6. **Ожидаемый результат**: Диалог закрывается, камера НЕ удалена
7. Снова нажмите "Удалить" и затем "Удалить" в диалоге подтверждения
8. **Ожидаемый результат**: Камера удалена, диалог редактирования закрыт

### Тест 4: Проверка на проблемной камере (192.168.1.201)
1. Подключитесь к камере 192.168.1.201
2. Проверьте работу фокуса
3. Попробуйте изменить настройки камеры
4. **Ожидаемый результат**: Все функции работают без вылетов приложения

## Технические детали

### Затронутые файлы
1. `app/src/main/java/com/example/denmediaipcams/network/PtzController.kt`
   - Исправлена логика переключения в ручной режим фокусировки
   - Увеличена скорость изменения фокуса

2. `app/src/main/java/com/example/denmediaipcams/ui/components/CameraEditDialog.kt`
   - Добавлен параметр `onDelete`
   - Добавлена кнопка удаления
   - Добавлен диалог подтверждения удаления

3. `app/src/main/java/com/example/denmediaipcams/MainActivity.kt`
   - Подключен обработчик удаления камеры

### Обратная совместимость
✅ Все изменения обратно совместимы
✅ Параметр `onDelete` опционален (nullable)
✅ Старые вызовы `CameraEditDialog` без `onDelete` продолжают работать

## Статус
✅ Исправление завершено
⏳ Требуется тестирование на реальном устройстве
