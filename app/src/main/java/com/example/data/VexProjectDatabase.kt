package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val payload: String,
    val updatedAt: Long
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects")
    fun deleteAll()

    @Query("DELETE FROM projects WHERE id = :projectId")
    fun deleteById(projectId: String)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class VexProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}
