package com.amandhakar.ledgerly.crypto.android.di

import com.amandhakar.ledgerly.crypto.android.CryptoManager
import com.amandhakar.ledgerly.crypto.android.CryptoManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds
    abstract fun bindCryptoManager(impl: CryptoManagerImpl): CryptoManager
}
