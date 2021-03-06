/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.advancedcoroutines

import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.example.android.advancedcoroutines.util.CacheOnSuccess
import com.example.android.advancedcoroutines.utils.ComparablePair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Repository module for handling data operations.
 *
 * This PlantRepository exposes two UI-observable database queries [plants] and
 * [getPlantsWithGrowZone].
 *
 * To update the plants cache, call [tryUpdateRecentPlantsForGrowZoneCache] or
 * [tryUpdateRecentPlantsCache].
 */
@ExperimentalCoroutinesApi
@FlowPreview
class PlantRepository private constructor(
        private val plantDao: PlantDao,
        private val plantService: NetworkService,
        private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * Fetch a list of [Plant]s from the database.
     * Returns a LiveData-wrapped List of Plants.
     */
    /*
    flow version. Any change to either plants or sortOrder will call plants.applySort(sortOrder).
     */
    val plantsFlow: Flow<List<Plant>>
        get() = plantDao.getPlantsFlow()
                .combine(customSortFlow) { plants, sortOrder ->
                    plants.applySort(sortOrder)
                }
                .flowOn(defaultDispatcher)
                .conflate()

    val plants: LiveData<List<Plant>> = liveData {
        val plantsLiveData = plantDao.getPlants() // another LiveData
        val customSortOrder = plantsListSortOrderCache.getOrAwait() // suspend function
        emitSource(plantsLiveData.map { plantList ->
            plantList.applySort(customSortOrder)
        })
    }

    //    val plants = plantDao.getPlants() // basic version: uses DAO directly.

    // in-memory cache for sorting. Returns an empty list in case of error, so as not to affect UI
    private var plantsListSortOrderCache = CacheOnSuccess(onErrorFallback = { listOf<String>() }) {
        plantService.customPlantSortOrder()
    }


    private val customSortFlow = plantsListSortOrderCache::getOrAwait.asFlow()
            .onStart {
                emit(listOf()) // no custom sort initially.
                delay(1500L)
            }
//    private val customSortFlow = plantsListSortOrderCache::getOrAwait.asFlow() // flow conversion for single function
//    private val customSortFlow = flow { emit(plantsListSortOrderCache.getOrAwait())} // basic flow expression


    /**
     * Fetch a list of [Plant]s from the database that matches a given [GrowZone].
     * Returns a LiveData-wrapped List of Plants.
     */
    // flow version
    fun getPlantsWithGrowZoneFlow(growZone: GrowZone): Flow<List<Plant>> {
        return plantDao.getPlantsWithGrowZoneNumberFlow(growZone.number)
                .map { plantList ->
                    // It is redundant process if request every time without cache.
                    val customSortOrder = plantsListSortOrderCache.getOrAwait()

                    val nextValue = plantList.applyMainSafeSort(customSortOrder)
                    nextValue
                }
    }

    fun getPlantsWithGrowZone(growZone: GrowZone): LiveData<List<Plant>> {
        return plantDao.getPlantsWithGrowZoneNumber(growZone.number)
                .switchMap { plantList ->
                    liveData {
                        // suspend function
                        val customSortOrder = plantsListSortOrderCache.getOrAwait()
                        // call another suspend function (heavy sort) and emit
                        emit(plantList.applyMainSafeSort(customSortOrder))
                    }
                }
    }

    // not safe-sort version
//    fun getPlantsWithGrowZone(growZone: GrowZone) = liveData<List<Plant>> {
//        val plantsWithGrowZoneLiveData = plantDao.getPlantsWithGrowZoneNumber(growZone.number)
//        val customSortOrder = plantsListSortOrderCache.getOrAwait()
//        emitSource(plantsWithGrowZoneLiveData.map { plantList ->
//            plantList.applySort(customSortOrder)
//        })
//    }

    // basic version: uses DAO directly.
//    fun getPlantsWithGrowZone(growZone: GrowZone) =
//            plantDao.getPlantsWithGrowZoneNumber(growZone.number)

    /**
     * Returns true if we should make a network request.
     */
    private fun shouldUpdatePlantsCache(): Boolean {
        // suspending function, so you can e.g. check the status of the database here
        return true
    }

    /**
     * Update the plants cache.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsCache() {
        if (shouldUpdatePlantsCache()) fetchRecentPlants()
    }

    /**
     * Update the plants cache for a specific grow zone.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsForGrowZoneCache(growZoneNumber: GrowZone) {
        if (shouldUpdatePlantsCache()) fetchPlantsForGrowZone(growZoneNumber)
    }

    /**
     * Fetch a new list of plants from the network, and append them to [plantDao]
     */
    private suspend fun fetchRecentPlants() {
        val plants = plantService.allPlants()
        plantDao.insertAll(plants)
    }

    /**
     * Fetch a list of plants for a grow zone from the network, and append them to [plantDao]
     */
    private suspend fun fetchPlantsForGrowZone(growZone: GrowZone) {
        val plants = plantService.plantsByGrowZone(growZone)
        plantDao.insertAll(plants)
    }

    private fun List<Plant>.applySort(customSortOrder: List<String>): List<Plant> {
        return sortedBy { plant ->
            val positionForItem = customSortOrder.indexOf(plant.plantId).let { order ->
                if (order > -1) order else Int.MAX_VALUE
            }
            ComparablePair(positionForItem, plant.name) // sort order is primary. second is alphabetical.
        }
    }

    suspend fun List<Plant>.applyMainSafeSort(customSortOrder: List<String>) =
            withContext(defaultDispatcher) {
                this@applyMainSafeSort.applySort(customSortOrder)
            }

    companion object {

        // For Singleton instantiation
        @Volatile
        private var instance: PlantRepository? = null

        fun getInstance(plantDao: PlantDao, plantService: NetworkService) =
                instance ?: synchronized(this) {
                    instance ?: PlantRepository(plantDao, plantService).also { instance = it }
                }
    }
}
