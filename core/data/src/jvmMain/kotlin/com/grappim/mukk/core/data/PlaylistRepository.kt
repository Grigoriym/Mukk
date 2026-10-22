package com.grappim.mukk.core.data

import com.grappim.mukk.core.model.Playlist
import com.grappim.mukk.core.model.PlaylistBrowseState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PlaylistRepository(
    private val databaseInit: DatabaseInit,
    private val trackRepository: TrackRepository,
    private val waveformRepository: WaveformRepository
) {

    suspend fun getAll(): List<Playlist> = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            PlaylistEntity.all().sortedBy { it.sortOrder }.map { it.toData() }
        }
    }

    suspend fun create(name: String, folderPath: String): Playlist = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val nextSortOrder = (PlaylistEntity.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
            PlaylistEntity.new {
                this.name = name
                this.folderPath = folderPath
                this.sortOrder = nextSortOrder
            }.toData()
        }
    }

    suspend fun rename(id: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val entity = PlaylistEntity.findById(id) ?: return@transaction false
            entity.name = name
            true
        }
    }

    suspend fun reorder(idsInNewOrder: List<Long>) = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            idsInNewOrder.forEachIndexed { index, id ->
                PlaylistEntity.findById(id)?.sortOrder = index
            }
        }
    }

    /** Saved browse positions, only for playlists that have one. */
    suspend fun getBrowseStates(): Map<Long, PlaylistBrowseState> = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            PlaylistEntity.all()
                .filter { it.selectedPath.isNotEmpty() }
                .associate { entity ->
                    entity.id.value to PlaylistBrowseState(
                        selectedPath = entity.selectedPath,
                        expandedPaths = entity.expandedPaths.split("|").filter { it.isNotEmpty() }.toSet()
                    )
                }
        }
    }

    suspend fun saveBrowseState(id: Long, state: PlaylistBrowseState): Boolean = withContext(Dispatchers.IO) {
        transaction(databaseInit.database) {
            val entity = PlaylistEntity.findById(id) ?: return@transaction false
            entity.selectedPath = state.selectedPath
            entity.expandedPaths = state.expandedPaths.joinToString("|")
            true
        }
    }

    suspend fun delete(id: Long): Boolean {
        val folderPath = withContext(Dispatchers.IO) {
            transaction(databaseInit.database) {
                val entity = PlaylistEntity.findById(id) ?: return@transaction null
                val path = entity.folderPath
                entity.delete()
                path
            }
        } ?: return false

        trackRepository.deleteByPathPrefix(folderPath)
        waveformRepository.deleteByPathPrefix(folderPath)
        return true
    }
}
