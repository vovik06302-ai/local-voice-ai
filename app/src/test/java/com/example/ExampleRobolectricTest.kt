package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.device.DeviceHardwareDetector
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessage
import com.example.data.remote.WebSearchService
import com.example.domain.LocalAssistantEngine
import com.example.domain.LocalModelManager
import com.example.domain.ModelCatalog
import com.example.domain.ModelState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Голосовой ИИ", appName)
  }

  @Test
  fun `verify BuildConfig bumped version name and code for release 1_1_0`() {
    assertEquals("1.1.0", BuildConfig.VERSION_NAME)
    assertEquals(2, BuildConfig.VERSION_CODE)
    assertEquals("com.aistudio.voiceai.localai", BuildConfig.APPLICATION_ID)
  }

  @Test
  fun `room database stores and preserves messages without destructive fallback`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = AppDatabase.getDatabase(context)
    val dao = db.chatDao()

    val testMsg = ChatMessage(
      sender = "user",
      text = "Тестовое сообщение для проверки сохранения истории",
      source = "local"
    )
    val insertedId = dao.insertMessage(testMsg)
    assertTrue(insertedId > 0)

    val messages = dao.getAllMessages().first()
    assertTrue(messages.any { it.text == "Тестовое сообщение для проверки сохранения истории" })
  }

  @Test
  fun `local assistant engine math evaluation`() = runBlocking {
    val engine = LocalAssistantEngine()
    val response = engine.processOffline("Сколько будет 10 + 25", emptyList())
    assertTrue(response.contains("35"))
  }

  @Test
  fun `local assistant engine greetings`() = runBlocking {
    val engine = LocalAssistantEngine()
    val response = engine.processOffline("Привет", emptyList())
    assertTrue(response.contains("персональный") || response.contains("Привет"))
  }

  @Test
  fun `web search service handles empty and blank queries gracefully`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val searchService = WebSearchService(context)
    val emptyRes = searchService.search("   ")
    assertTrue(emptyRes is com.example.data.remote.SearchResponse.Error)
  }

  @Test
  fun `device hardware detector returns valid ram and storage specs`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val specs = DeviceHardwareDetector.detect(context)
    assertTrue(specs.totalRamBytes > 0)
    assertTrue(specs.cpuCores > 0)
    assertNotNull(specs.cpuAbi)
  }

  @Test
  fun `model catalog contains valid Qwen models for on-device inference`() {
    val models = ModelCatalog.AVAILABLE_MODELS
    assertEquals(2, models.size)
    val qwen15 = ModelCatalog.getById("qwen2.5-1.5b-q4")
    assertTrue(qwen15.downloadSizeBytes > 500_000_000L)
    assertTrue(qwen15.requiredStorageBytes > qwen15.downloadSizeBytes)
  }

  @Test
  fun `local model manager initializes states safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = LocalModelManager(context)
    val states = manager.modelState.value
    assertTrue(states.containsKey("qwen2.5-1.5b-q4"))
    assertEquals(ModelState.NotDownloaded, states["qwen2.5-1.5b-q4"])
  }
}
