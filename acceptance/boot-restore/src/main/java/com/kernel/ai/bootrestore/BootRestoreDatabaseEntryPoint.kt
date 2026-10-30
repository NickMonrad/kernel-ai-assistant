package com.kernel.ai.bootrestore

import com.kernel.ai.core.memory.KernelDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootRestoreDatabaseEntryPoint {
    fun kernelDatabase(): KernelDatabase
}
