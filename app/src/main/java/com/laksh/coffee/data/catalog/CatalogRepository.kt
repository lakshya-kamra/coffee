package com.laksh.coffee.data.catalog

import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.catalog.BeanVocabulary
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.catalog.CatalogOrigin
import com.laksh.coffee.domain.catalog.CatalogProvider
import com.laksh.coffee.domain.catalog.CatalogRoaster
import com.laksh.coffee.domain.catalog.CatalogWater
import com.laksh.coffee.domain.catalog.SearchHit
import com.laksh.coffee.domain.catalog.SearchIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Suspend access to [Catalog] for ViewModels: the first read of a list parses JSON, which must
 * never happen on the main thread.
 */
class CatalogRepository(private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun grinders(): List<CatalogGrinder> = withContext(io) { Catalog.grinders }
    suspend fun roasters(): List<CatalogRoaster> = withContext(io) { Catalog.roasters }
    suspend fun origins(): List<CatalogOrigin> = withContext(io) { Catalog.origins }
    suspend fun beans(): BeanVocabulary = withContext(io) { Catalog.beans }
    suspend fun brewers(): List<Brewer> = withContext(io) { Catalog.brewers }
    suspend fun water(): List<CatalogWater> = withContext(io) { Catalog.water }
    suspend fun providers(): List<CatalogProvider> = withContext(io) { Catalog.providers }

    // One index per kind for the life of the process: the catalog never changes while it runs.
    private val indexes = ConcurrentHashMap<CatalogKind, SearchIndex>()

    /** The searchable docs for [kind], built once (off the main thread) and cached. */
    suspend fun index(kind: CatalogKind): SearchIndex = withContext(Dispatchers.Default) {
        indexes.getOrPut(kind) { SearchIndex(CatalogDocs.build(kind)) }
    }

    /** Ranked hits; an empty [query] gives every entry in group order. */
    suspend fun search(kind: CatalogKind, query: String): List<SearchHit> = withContext(Dispatchers.Default) {
        indexes.getOrPut(kind) { SearchIndex(CatalogDocs.build(kind)) }.search(query)
    }
}
