package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AspectRatio
import com.example.data.Project
import com.example.data.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VexEdit Studio", appName)
  }

  @Test
  fun `verify project creation and persistence lifecycle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = ProjectRepository(context)

    // Create a new real project
    val newProject = Project(
      title = "Cyberpunk Reel",
      aspectRatio = AspectRatio.RATIO_9_16
    )
    repo.saveProject(newProject)

    val savedList = repo.projects.value
    val found = savedList.find { it.id == newProject.id }
    assertNotNull(found)
    assertEquals("Cyberpunk Reel", found?.title)

    // Rename project
    repo.renameProject(newProject.id, "Cyberpunk Reel V2")
    val renamed = repo.projects.value.find { it.id == newProject.id }
    assertEquals("Cyberpunk Reel V2", renamed?.title)

    // Duplicate project
    val duplicated = repo.duplicateProject(newProject.id)
    assertNotNull(duplicated)
    assertEquals("Cyberpunk Reel V2 (Copy)", duplicated?.title)

    // Delete project
    repo.deleteProject(newProject.id)
    assertNull(repo.projects.value.find { it.id == newProject.id })
  }

  @Test
  fun `verify video clip trimming can shorten and lengthen back up to source duration`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)

    val clip = com.example.data.MediaClip(
      id = "test_clip_1",
      name = "Sample Video.mp4",
      mediaType = com.example.data.MediaType.VIDEO,
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 0L,
      sourceStartMs = 0L,
      durationMs = 10000L,
      sourceDurationMs = 10000L
    )

    val project = com.example.data.Project(
      id = "proj_test_trim",
      title = "Trim Test Project",
      clips = listOf(clip),
      durationMs = 10000L
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    // Shorten end by 4 seconds (duration becomes 6000ms)
    vm.trimClipEnd("test_clip_1", -4000L)
    val trimmedClip = vm.currentProject.value?.clips?.find { it.id == "test_clip_1" }
    assertEquals(6000L, trimmedClip?.durationMs)

    // Lengthen end back by 3 seconds (duration becomes 9000ms)
    vm.trimClipEnd("test_clip_1", 3000L)
    val lengthenedClip = vm.currentProject.value?.clips?.find { it.id == "test_clip_1" }
    assertEquals(9000L, lengthenedClip?.durationMs)

    // Shorten start by 2 seconds (sourceStartMs becomes 2000ms, duration becomes 7000ms)
    vm.trimClipStart("test_clip_1", 2000L)
    val startTrimmedClip = vm.currentProject.value?.clips?.find { it.id == "test_clip_1" }
    assertEquals(2000L, startTrimmedClip?.sourceStartMs)
    assertEquals(7000L, startTrimmedClip?.durationMs)

    // Lengthen start back by 1 second (sourceStartMs becomes 1000ms, duration becomes 8000ms)
    vm.trimClipStart("test_clip_1", -1000L)
    val startExtendedClip = vm.currentProject.value?.clips?.find { it.id == "test_clip_1" }
    assertEquals(1000L, startExtendedClip?.sourceStartMs)
    assertEquals(8000L, startExtendedClip?.durationMs)
  }
}
