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
  @Test
  fun `split preserves timeline gaps and maps source time using clip speed`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)

    val clipToSplit = com.example.data.MediaClip(
      id = "split_target",
      name = "Main Video.mp4",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 5000L,
      sourceStartMs = 1000L,
      durationMs = 10000L,
      sourceDurationMs = 20000L,
      speed = 1.5f
    )
    val laterClip = com.example.data.MediaClip(
      id = "later_clip",
      name = "Later Video.mp4",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 25000L,
      durationMs = 5000L,
      sourceDurationMs = 5000L
    )
    val project = com.example.data.Project(
      id = "split_gap_test",
      title = "Split Gap Test",
      clips = listOf(clipToSplit, laterClip),
      durationMs = 30000L
    )

    vm.projectRepo.saveProject(project)
    vm.selectProject(project)
    vm.selectClip(clipToSplit.id)
    vm.setPlayheadPosition(9000L)
    vm.splitClipAtPlayhead()

    val clips = vm.currentProject.value!!.clips
      .filter { it.trackId == "track_video_1" }
      .sortedBy { it.startTimeMs }

    assertEquals(3, clips.size)
    assertEquals(5000L, clips[0].startTimeMs)
    assertEquals(4000L, clips[0].durationMs)
    assertEquals(9000L, clips[1].startTimeMs)
    assertEquals(6000L, clips[1].durationMs)
    assertEquals(7000L, clips[1].sourceStartMs)
    assertEquals(25000L, clips[2].startTimeMs)
    assertEquals(30000L, vm.currentProject.value?.durationMs)
  }


  @Test
  fun `delete supports both preserving gaps and ripple behavior`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val before = com.example.data.MediaClip(
      id = "before_delete", name = "Before", trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO, startTimeMs = 0L, durationMs = 1000L
    )
    val target = com.example.data.MediaClip(
      id = "delete_target", name = "Delete", trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO, startTimeMs = 2000L, durationMs = 1000L
    )
    val after = com.example.data.MediaClip(
      id = "after_delete", name = "After", trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO, startTimeMs = 6000L, durationMs = 1000L
    )
    val project = com.example.data.Project(
      id = "delete_gap_modes", title = "Delete Gap Modes",
      clips = listOf(before, target, after), durationMs = 7000L
    )

    vm.projectRepo.saveProject(project)
    vm.selectProject(project)
    vm.deleteClip(target.id, ripple = false)
    assertEquals(6000L, vm.currentProject.value?.clips?.first { it.id == after.id }?.startTimeMs)

    val rippleProject = project.copy(id = "ripple_delete_modes")
    vm.projectRepo.saveProject(rippleProject)
    vm.selectProject(rippleProject)
    vm.deleteClip(target.id, ripple = true)
    assertEquals(5000L, vm.currentProject.value?.clips?.first { it.id == after.id }?.startTimeMs)
  }

  @Test
  fun `reordering clips preserves existing timeline slots and gaps`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clips = listOf(
      com.example.data.MediaClip(
        id = "slot_a", name = "A", trackId = "track_video_1",
        trackType = com.example.data.TrackType.VIDEO, startTimeMs = 0L, durationMs = 1000L
      ),
      com.example.data.MediaClip(
        id = "slot_b", name = "B", trackId = "track_video_1",
        trackType = com.example.data.TrackType.VIDEO, startTimeMs = 4000L, durationMs = 1000L
      ),
      com.example.data.MediaClip(
        id = "slot_c", name = "C", trackId = "track_video_1",
        trackType = com.example.data.TrackType.VIDEO, startTimeMs = 9000L, durationMs = 1000L
      )
    )
    val project = com.example.data.Project(
      id = "reorder_gap_slots", title = "Reorder Gap Slots",
      clips = clips, durationMs = 10000L
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)
    vm.reorderTrackClips("track_video_1", 0, 2)

    val updated = vm.currentProject.value!!.clips.associateBy { it.id }
    assertEquals(9000L, updated["slot_a"]?.startTimeMs)
    assertEquals(0L, updated["slot_b"]?.startTimeMs)
    assertEquals(4000L, updated["slot_c"]?.startTimeMs)
  }
  @Test
  fun `keyframe navigation jumps to actual keyframe timestamps and add stores all transform values`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clip = com.example.data.MediaClip(
      id = "keyframe_navigation_clip",
      name = "Keyframe Test",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 2000L,
      durationMs = 10000L,
      keyframes = listOf(
        com.example.data.Keyframe(timeMs = 1000L, param = com.example.data.KeyframeParam.POSITION_X, value = 10f),
        com.example.data.Keyframe(timeMs = 4000L, param = com.example.data.KeyframeParam.POSITION_Y, value = 20f),
        com.example.data.Keyframe(timeMs = 6000L, param = com.example.data.KeyframeParam.OPACITY, value = 80f)
      )
    )
    val project = com.example.data.Project(
      id = "keyframe_navigation_project",
      title = "Keyframe Navigation",
      clips = listOf(clip),
      durationMs = 12000L
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    vm.setPlayheadPosition(6500L)
    vm.jumpToPreviousKeyframe(clip.id)
    assertEquals(6000L, vm.playheadPositionMs.value)

    vm.jumpToNextKeyframe(clip.id)
    assertEquals(8000L, vm.playheadPositionMs.value)

    vm.setPlayheadPosition(5000L)
    vm.addOrUpdateKeyframes(
      clip.id,
      mapOf(
        com.example.data.KeyframeParam.POSITION_X to 5f,
        com.example.data.KeyframeParam.POSITION_Y to 6f,
        com.example.data.KeyframeParam.SCALE to 120f,
        com.example.data.KeyframeParam.ROTATION to 15f,
        com.example.data.KeyframeParam.OPACITY to 90f
      ),
      com.example.data.InterpolationType.LINEAR
    )
    val saved = vm.currentProject.value!!.clips.first { it.id == clip.id }
    com.example.data.KeyframeParam.values().forEach { param ->
      assertNotNull(saved.keyframes.firstOrNull { it.param == param && it.timeMs == 3000L })
    }
  }

  @Test
  fun `selected keyframe value and easing can be edited and undone`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val keyframe = com.example.data.Keyframe(
      id = "editable_keyframe",
      timeMs = 1000L,
      param = com.example.data.KeyframeParam.POSITION_X,
      value = 10f,
      curve = com.example.data.InterpolationType.LINEAR
    )
    val clip = com.example.data.MediaClip(
      id = "editable_keyframe_clip",
      name = "Keyframe Edit Test",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      durationMs = 5000L,
      keyframes = listOf(keyframe)
    )
    val project = com.example.data.Project(
      id = "editable_keyframe_project",
      title = "Editable Keyframe",
      clips = listOf(clip),
      durationMs = 5000L
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    vm.updateKeyframe(
      clip.id,
      keyframe.id,
      value = 42f,
      curve = com.example.data.InterpolationType.EASE_OUT
    )
    val updated = vm.currentProject.value!!.clips.first { it.id == clip.id }.keyframes.first()
    assertEquals(42f, updated.value)
    assertEquals(com.example.data.InterpolationType.EASE_OUT, updated.curve)

    vm.undo()
    val restored = vm.currentProject.value!!.clips.first { it.id == clip.id }.keyframes.first()
    assertEquals(10f, restored.value)
    assertEquals(com.example.data.InterpolationType.LINEAR, restored.curve)
  }

  @Test
  fun `audio properties are clamped and mixer changes are undoable`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clip = com.example.data.MediaClip(
      id = "audio_properties_clip",
      name = "Audio Properties Test",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = "track_audio_1",
      trackType = com.example.data.TrackType.AUDIO_MUSIC,
      audioProperties = com.example.data.AudioProperties()
    )
    val project = com.example.data.Project(
      id = "audio_properties_project",
      title = "Audio Properties",
      clips = listOf(clip)
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    vm.updateAudioProperties(
      clip.id,
      clip.audioProperties.copy(
        volume = 2f,
        fadeInMs = -100L,
        fadeOutMs = -200L,
        reverbLevel = 2f,
        delayLevel = -1f,
        eqPreset = "Rock"
      )
    )
    val edited = vm.currentProject.value!!.clips.first { it.id == clip.id }.audioProperties
    assertEquals(1f, edited.volume)
    assertEquals(0L, edited.fadeInMs)
    assertEquals(0L, edited.fadeOutMs)
    assertEquals(1f, edited.reverbLevel)
    assertEquals(0f, edited.delayLevel)
    assertEquals("Rock", edited.eqPreset)

    vm.undo()
    val restored = vm.currentProject.value!!.clips.first { it.id == clip.id }.audioProperties
    assertEquals(1f, restored.volume)
    assertEquals(0L, restored.fadeInMs)
    assertEquals(0f, restored.reverbLevel)

    vm.setTrackFader("track_audio_1", 100f)
    assertEquals(6f, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.faderDb)
    vm.undo()
    assertEquals(0f, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.faderDb)
  }

  @Test
  fun `project aspect ratio change is undoable`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val project = com.example.data.Project(id = "aspect_undo_project", title = "Aspect Undo")
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    vm.setProjectAspectRatio(AspectRatio.RATIO_16_9)
    assertEquals(AspectRatio.RATIO_16_9, vm.currentProject.value?.aspectRatio)
    vm.undo()
    assertEquals(AspectRatio.RATIO_9_16, vm.currentProject.value?.aspectRatio)
  }

  @Test
  fun `split targets clip under playhead when selected clip is elsewhere`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val selectedElsewhere = com.example.data.MediaClip(
      id = "selected_elsewhere",
      name = "Selected",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 0L,
      durationMs = 2000L
    )
    val clipUnderPlayhead = com.example.data.MediaClip(
      id = "clip_under_playhead",
      name = "Target",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 3000L,
      durationMs = 4000L
    )
    val project = com.example.data.Project(
      id = "split_under_playhead",
      title = "Split Under Playhead",
      clips = listOf(selectedElsewhere, clipUnderPlayhead),
      durationMs = 7000L
    )
    vm.selectProject(project)
    vm.selectClip(selectedElsewhere.id)
    vm.setPlayheadPosition(5000L)

    vm.splitClipAtPlayhead()

    val clips = vm.currentProject.value!!.clips
    assertEquals(3, clips.size)
    assertEquals(2000L, clips.first { it.id == clipUnderPlayhead.id }.durationMs)
    assertEquals(true, clips.any { it.name == "Target (Part 2)" && it.startTimeMs == 5000L })
    assertEquals("Target (Part 2)", clips.first { it.id == vm.selectedClipId.value }.name)
  }

  @Test
  fun `timeline moves audio clips between compatible tracks as one undoable edit`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val audioClip = com.example.data.MediaClip(
      id = "movable_audio",
      name = "Voice Take",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = "track_audio_1",
      trackType = com.example.data.TrackType.AUDIO_MUSIC,
      startTimeMs = 1200L,
      durationMs = 3500L
    )
    val project = com.example.data.Project(
      id = "cross_track_project",
      title = "Cross Track",
      clips = listOf(audioClip),
      durationMs = 4700L
    )
    vm.selectProject(project)

    vm.moveClipToTrack(audioClip.id, "track_audio_voice", 1500L)
    val moved = vm.currentProject.value!!.clips.single()
    assertEquals("track_audio_voice", moved.trackId)
    assertEquals(com.example.data.TrackType.AUDIO_VOICE, moved.trackType)
    assertEquals(1500L, moved.startTimeMs)

    // An audio clip must not be reassigned to a video lane.
    vm.moveClipToTrack(audioClip.id, "track_video_1", 1800L)
    assertEquals("track_audio_voice", vm.currentProject.value!!.clips.single().trackId)
    assertEquals(1500L, vm.currentProject.value!!.clips.single().startTimeMs)

    vm.undo()
    val restored = vm.currentProject.value!!.clips.single()
    assertEquals("track_audio_1", restored.trackId)
    assertEquals(1200L, restored.startTimeMs)
  }

  @Test
  fun `timeline audio track mute and solo controls update project state`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val project = com.example.data.Project(
      id = "track_controls_project",
      title = "Track Controls"
    )
    vm.selectProject(project)

    vm.toggleTrackMute("track_audio_1")
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.isMuted)

    vm.toggleTrackSolo("track_audio_1")
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.isSolo)

    vm.undo()
    assertEquals(false, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.isSolo)
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == "track_audio_1" }.isMuted)
  }

  @Test
  fun `timeline snapping aligns clip start and end to nearby clip edges`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val moving = com.example.data.MediaClip(
      id = "snap_moving",
      name = "Moving Clip",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 1000L,
      durationMs = 2000L
    )
    val neighbor = com.example.data.MediaClip(
      id = "snap_neighbor",
      name = "Neighbor",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 5000L,
      durationMs = 1000L
    )
    val project = com.example.data.Project(
      id = "timeline_snap_project",
      title = "Timeline Snap",
      clips = listOf(moving, neighbor),
      durationMs = 6000L
    )
    vm.selectProject(project)

    val startSnap = vm.findTimelineSnap(moving.id, 4920L, 120L)
    assertEquals(5000L, startSnap?.first)
    assertEquals(5000L, startSnap?.second)

    val endSnap = vm.findTimelineSnap(moving.id, 3100L, 120L)
    assertEquals(3000L, endSnap?.first)
    assertEquals(5000L, endSnap?.second)

    assertNull(vm.findTimelineSnap(moving.id, 20000L, 100L))

    assertEquals(5000L, vm.findTimelineBoundarySnap(moving.id, 4920L, 120L))
    assertEquals(5000L, vm.findTimelineBoundarySnap(moving.id, 5080L, 120L))
    assertNull(vm.findTimelineBoundarySnap(moving.id, 20000L, 100L))
  }

  @Test
  fun `timeline clip can move to an empty time range and undo restores its position`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clip = com.example.data.MediaClip(
      id = "free_move_clip",
      name = "Movable Clip",
      trackId = "track_video_1",
      trackType = com.example.data.TrackType.VIDEO,
      startTimeMs = 1000L,
      durationMs = 3000L
    )
    val project = com.example.data.Project(
      id = "free_move_project",
      title = "Free Move",
      clips = listOf(clip),
      durationMs = 4000L
    )
    vm.projectRepo.saveProject(project)
    vm.selectProject(project)

    vm.moveClip(clip.id, 8500L)
    assertEquals(8500L, vm.currentProject.value!!.clips.first { it.id == clip.id }.startTimeMs)
    assertEquals(11500L, vm.currentProject.value!!.durationMs)

    vm.undo()
    assertEquals(1000L, vm.currentProject.value!!.clips.first { it.id == clip.id }.startTimeMs)
    assertEquals(4000L, vm.currentProject.value!!.durationMs)
  }


  @Test
  fun `trimming clip end ripples later clips on same track only`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val tracks = Project.defaultTracks()
    val audioTrack = tracks.first { it.type == com.example.data.TrackType.AUDIO_MUSIC }
    val otherAudioTrack = tracks.first { it.type == com.example.data.TrackType.AUDIO_VOICE }
    val first = com.example.data.MediaClip(
      id = "trim_ripple_first",
      name = "First",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = audioTrack.id,
      trackType = audioTrack.type,
      startTimeMs = 0L,
      durationMs = 2000L
    )
    val laterSameLane = com.example.data.MediaClip(
      id = "trim_ripple_later",
      name = "Later",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = audioTrack.id,
      trackType = audioTrack.type,
      startTimeMs = 3000L,
      durationMs = 1000L
    )
    val otherLane = com.example.data.MediaClip(
      id = "trim_ripple_other",
      name = "Other Lane",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = otherAudioTrack.id,
      trackType = otherAudioTrack.type,
      startTimeMs = 3000L,
      durationMs = 1000L
    )
    vm.selectProject(Project(
      id = "trim_ripple_project",
      title = "Trim Ripple",
      tracks = tracks,
      clips = listOf(first, laterSameLane, otherLane),
      durationMs = 4000L
    ))

    vm.trimClipEnd(first.id, 500L)

    val clips = vm.currentProject.value!!.clips.associateBy { it.id }
    assertEquals(2500L, clips.getValue(first.id).durationMs)
    assertEquals(3500L, clips.getValue(laterSameLane.id).startTimeMs)
    assertEquals(3000L, clips.getValue(otherLane.id).startTimeMs)

    vm.undo()
    val undone = vm.currentProject.value!!.clips.associateBy { it.id }
    assertEquals(2000L, undone.getValue(first.id).durationMs)
    assertEquals(3000L, undone.getValue(laterSameLane.id).startTimeMs)

    vm.redo()
    val redone = vm.currentProject.value!!.clips.associateBy { it.id }
    assertEquals(2500L, redone.getValue(first.id).durationMs)
    assertEquals(3500L, redone.getValue(laterSameLane.id).startTimeMs)
  }

  @Test
  fun `reordering timeline tracks keeps clips attached and supports undo`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val tracks = Project.defaultTracks()
    val clip = com.example.data.MediaClip(
      id = "track_reorder_clip",
      name = "Attached Clip",
      trackId = tracks.first().id,
      trackType = tracks.first().type,
      startTimeMs = 1200L,
      durationMs = 2400L
    )
    val project = Project(
      id = "track_reorder_project",
      title = "Track Reorder",
      tracks = tracks,
      clips = listOf(clip),
      durationMs = 3600L
    )
    vm.selectProject(project)

    vm.reorderTracks(0, 1)

    val reordered = vm.currentProject.value!!
    assertEquals(tracks[1].id, reordered.tracks[0].id)
    assertEquals(tracks[0].id, reordered.clips.single().trackId)
    assertEquals(1200L, reordered.clips.single().startTimeMs)

    vm.undo()
    val restored = vm.currentProject.value!!
    assertEquals(tracks.map { it.id }, restored.tracks.map { it.id })
    assertEquals(tracks[0].id, restored.clips.single().trackId)
  }

  @Test
  fun `timeline track mute and solo toggles persist in project state and undo`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val tracks = Project.defaultTracks()
    val audioTrack = tracks.first { it.type == com.example.data.TrackType.AUDIO_MUSIC }
    vm.selectProject(Project(
      id = "track_mute_solo_project",
      title = "Track Mute Solo",
      tracks = tracks
    ))

    vm.toggleTrackMute(audioTrack.id)
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == audioTrack.id }.isMuted)
    vm.toggleTrackSolo(audioTrack.id)
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == audioTrack.id }.isSolo)

    vm.undo()
    assertEquals(false, vm.currentProject.value!!.tracks.first { it.id == audioTrack.id }.isSolo)
    assertEquals(true, vm.currentProject.value!!.tracks.first { it.id == audioTrack.id }.isMuted)
  }

  @Test
  fun `moving clip between compatible tracks preserves time and rejects incompatible tracks`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val tracks = Project.defaultTracks()
    val music = tracks.first { it.type == com.example.data.TrackType.AUDIO_MUSIC }
    val voice = tracks.first { it.type == com.example.data.TrackType.AUDIO_VOICE }
    val video = tracks.first { it.type == com.example.data.TrackType.VIDEO }
    val clip = com.example.data.MediaClip(
      id = "move_audio_clip",
      name = "Voiceover",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = music.id,
      trackType = music.type,
      startTimeMs = 4200L,
      durationMs = 1800L
    )
    vm.selectProject(Project(
      id = "move_audio_project",
      title = "Move Audio",
      tracks = tracks,
      clips = listOf(clip),
      durationMs = 6000L
    ))

    vm.moveClipToTrack(clip.id, voice.id)
    val moved = vm.currentProject.value!!.clips.single()
    assertEquals(voice.id, moved.trackId)
    assertEquals(voice.type, moved.trackType)
    assertEquals(4200L, moved.startTimeMs)

    vm.moveClipToTrack(clip.id, video.id)
    val afterRejectedMove = vm.currentProject.value!!.clips.single()
    assertEquals(voice.id, afterRejectedMove.trackId)
    assertEquals(4200L, afterRejectedMove.startTimeMs)
  }

  @Test
  fun `project persistence retains mixer tracks and audio processing settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = ProjectRepository(context)
    val audioClip = com.example.data.MediaClip(
      id = "persist_audio_clip",
      name = "Persist Audio",
      mediaType = com.example.data.MediaType.AUDIO,
      trackId = "custom_music_track",
      trackType = com.example.data.TrackType.AUDIO_MUSIC,
      audioProperties = com.example.data.AudioProperties(
        volume = 0.72f,
        isMuted = true,
        isSolo = false,
        fadeInMs = 1200L,
        fadeOutMs = 900L,
        faderDb = -3.5f,
        eqPreset = "Rock",
        noiseGateEnabled = true,
        compressorEnabled = true,
        reverbLevel = 0.4f,
        delayLevel = 0.2f,
        limiterEnabled = true
      )
    )
    val project = com.example.data.Project(
      id = "persist_audio_project",
      title = "Audio Persistence",
      tracks = Project.defaultTracks().map {
        if (it.id == "track_audio_1") it.copy(title = "Custom Music", isMuted = true, isSolo = true, faderDb = -4.5f) else it
      },
      clips = listOf(audioClip)
    )
    repository.saveProject(project)

    // saveProject persists through the repository's IO queue; wait for the Room/JSON
    // write to finish before constructing a fresh repository instance.
    val persistenceFile = java.io.File(context.filesDir, "vex_projects.json")
    var persistenceAttempts = 0
    while (
      persistenceAttempts < 100 &&
      (!persistenceFile.exists() || !persistenceFile.readText().contains(project.id))
    ) {
      Thread.sleep(50)
      persistenceAttempts++
    }
    val restoredRepository = ProjectRepository(context)
    var restored: Project? = null
    var loadAttempts = 0
    while (restored == null && loadAttempts < 100) {
      restored = restoredRepository.projects.value.firstOrNull { it.id == project.id }
      if (restored == null) {
        Thread.sleep(50)
        loadAttempts++
      }
    }
    val loadedProject = requireNotNull(restored) { "Persisted project did not load within 5 seconds" }
    val restoredTrack = loadedProject.tracks.first { it.id == "track_audio_1" }
    val restoredAudio = loadedProject.clips.first { it.id == audioClip.id }.audioProperties
    assertEquals("Custom Music", restoredTrack.title)
    assertEquals(true, restoredTrack.isMuted)
    assertEquals(true, restoredTrack.isSolo)
    assertEquals(-4.5f, restoredTrack.faderDb)
    assertEquals("Rock", restoredAudio.eqPreset)
    assertEquals(1200L, restoredAudio.fadeInMs)
    assertEquals(900L, restoredAudio.fadeOutMs)
    assertEquals(true, restoredAudio.noiseGateEnabled)
    assertEquals(true, restoredAudio.compressorEnabled)
    assertEquals(true, restoredAudio.limiterEnabled)
    assertEquals(0.4f, restoredAudio.reverbLevel)
    assertEquals(0.2f, restoredAudio.delayLevel)
  }



  @Test
  fun `bulk timeline delete preserves gaps and is undoable as one action`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clips = listOf(
      com.example.data.MediaClip(id = "bulk_a", name = "A", trackId = "track_video_1",
        trackType = com.example.data.TrackType.VIDEO, startTimeMs = 0L, durationMs = 1000L),
      com.example.data.MediaClip(id = "bulk_b", name = "B", trackId = "track_video_1",
        trackType = com.example.data.TrackType.VIDEO, startTimeMs = 3000L, durationMs = 1000L),
      com.example.data.MediaClip(id = "bulk_c", name = "C", trackId = "track_audio_1",
        trackType = com.example.data.TrackType.AUDIO_MUSIC, mediaType = com.example.data.MediaType.AUDIO,
        startTimeMs = 5000L, durationMs = 2000L)
    )
    vm.selectProject(com.example.data.Project(
      id = "bulk_delete_project", title = "Bulk Delete", clips = clips, durationMs = 7000L
    ))

    vm.deleteClips(setOf("bulk_a", "bulk_c"))
    assertEquals(listOf("bulk_b"), vm.currentProject.value!!.clips.map { it.id })
    assertEquals(4000L, vm.currentProject.value!!.durationMs)

    vm.undo()
    assertEquals(3, vm.currentProject.value!!.clips.size)
    assertEquals(5000L, vm.currentProject.value!!.clips.first { it.id == "bulk_c" }.startTimeMs)
    vm.redo()
    assertEquals(listOf("bulk_b"), vm.currentProject.value!!.clips.map { it.id })
  }

  @Test
  fun `bulk move is atomic preserves timing and supports undo redo`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.StudioViewModel(application)
    val clips = listOf(
      com.example.data.MediaClip(id = "bulk_audio_a", name = "Voice A",
        mediaType = com.example.data.MediaType.AUDIO, trackId = "track_audio_1",
        trackType = com.example.data.TrackType.AUDIO_MUSIC, startTimeMs = 1200L, durationMs = 900L),
      com.example.data.MediaClip(id = "bulk_audio_b", name = "Voice B",
        mediaType = com.example.data.MediaType.AUDIO, trackId = "track_audio_2",
        trackType = com.example.data.TrackType.AUDIO_MUSIC, startTimeMs = 4000L, durationMs = 1500L)
    )
    vm.selectProject(com.example.data.Project(
      id = "bulk_move_project", title = "Bulk Move", clips = clips, durationMs = 5500L
    ))

    assertEquals(true, vm.moveClipsToTrack(setOf("bulk_audio_a", "bulk_audio_b"), "track_audio_voice"))
    val moved = vm.currentProject.value!!.clips.associateBy { it.id }
    assertEquals("track_audio_voice", moved["bulk_audio_a"]?.trackId)
    assertEquals("track_audio_voice", moved["bulk_audio_b"]?.trackId)
    assertEquals(1200L, moved["bulk_audio_a"]?.startTimeMs)
    assertEquals(4000L, moved["bulk_audio_b"]?.startTimeMs)

    assertEquals(false, vm.moveClipsToTrack(setOf("bulk_audio_a", "bulk_audio_b"), "track_video_1"))
    assertEquals("track_audio_voice", vm.currentProject.value!!.clips.first { it.id == "bulk_audio_a" }.trackId)
    vm.undo()
    assertEquals("track_audio_1", vm.currentProject.value!!.clips.first { it.id == "bulk_audio_a" }.trackId)
    vm.redo()
    assertEquals("track_audio_voice", vm.currentProject.value!!.clips.first { it.id == "bulk_audio_a" }.trackId)
  }


}
