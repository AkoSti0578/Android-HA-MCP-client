package nl.ako.hachat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import nl.ako.hachat.core.CleartextPolicy
import nl.ako.hachat.data.anthropic.AnthropicClient
import nl.ako.hachat.data.chat.ChatRepository
import nl.ako.hachat.data.db.AppDatabase
import nl.ako.hachat.data.mcp.McpRepository
import nl.ako.hachat.data.settings.SecretCipher
import nl.ako.hachat.data.settings.SettingsRepository
import nl.ako.hachat.data.settings.TinkSecretCipher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun cipher(@ApplicationContext ctx: Context): SecretCipher = TinkSecretCipher(ctx)

    @Provides @Singleton
    fun settings(@ApplicationContext ctx: Context, cipher: SecretCipher) = SettingsRepository(ctx.settingsStore, cipher)

    /** De enige HTTP-client van de app; afgedwongen: cleartext alleen naar privé-adressen. Geen logging (geheimen!). */
    @Provides @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(CleartextPolicy.Guard())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun anthropic(http: OkHttpClient, settings: SettingsRepository) =
        AnthropicClient(http, apiKeyProvider = { settings.currentSecrets().apiKey })

    @Provides @Singleton
    fun mcp(http: OkHttpClient, settings: SettingsRepository) = McpRepository(http, settings)

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "ako-ha-chat.db").build()

    @Provides @Singleton
    fun chatRepository(db: AppDatabase) = ChatRepository(db.chatDao())
}
