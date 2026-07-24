package com.amandhakar.ledgerly.update.di

import com.amandhakar.ledgerly.update.AndroidUpdateInstaller
import com.amandhakar.ledgerly.update.GithubUpdateChecker
import com.amandhakar.ledgerly.update.UpdateChecker
import com.amandhakar.ledgerly.update.UpdateInstaller
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
