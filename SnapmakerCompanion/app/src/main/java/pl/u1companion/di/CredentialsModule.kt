package pl.u1companion.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.u1companion.data.settings.ApiCredentials
import pl.u1companion.data.settings.SettingsRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialsModule {
    @Binds abstract fun credentials(impl: SettingsRepository): ApiCredentials
}
