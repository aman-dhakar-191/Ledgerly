package com.amandhakar.ledgerly.update.di

import com.amandhakar.ledgerly.update.UpdateChecker
import com.amandhakar.ledgerly.update.UpdateInstaller
import com.amandhakar.ledgerly.update.android.AndroidUpdateInstaller
import com.amandhakar.ledgerly.update.android.GithubUpdateChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    abstract fun bindUpdateChecker(impl: GithubUpdateChecker): UpdateChecker

    @Binds
    abstract fun bindUpdateInstaller(impl: AndroidUpdateInstaller): UpdateInstaller
}
