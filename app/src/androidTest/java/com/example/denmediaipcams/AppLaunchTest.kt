package com.example.denmediaipcams

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Приложение запускается: главный экран и MainViewModel создаются без падения (в том числе все фоновые наблюдатели,
 * которые стартуют прямо из init модели) и остаются живыми после первых секунд работы.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @Test
    fun mainActivityStartsAndStaysAlive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(4_000)
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }
}
