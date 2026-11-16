package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalLimitsTest {

    @Test
    fun `Body never goes above the engine's own target`() {
        assertEquals(40.0, ProposalLimits.clamp(Realm.BODY, TargetUnit.REPS, 60.0, reference = 40.0), 1e-9)
        assertEquals(20.0, ProposalLimits.clamp(Realm.BODY, TargetUnit.REPS, 5.0, reference = 40.0), 1e-9)
        assertEquals(30.0, ProposalLimits.clamp(Realm.BODY, TargetUnit.REPS, 30.0, reference = 40.0), 1e-9)
    }

    @Test
    fun `Mind and Self stay within half to one and a half times the reference`() {
        assertEquals(90.0, ProposalLimits.clamp(Realm.MIND, TargetUnit.MINUTES, 120.0, reference = 60.0), 1e-9)
        assertEquals(75.0, ProposalLimits.clamp(Realm.MIND, TargetUnit.MINUTES, 75.0, reference = 60.0), 1e-9)
        assertEquals(30.0, ProposalLimits.clamp(Realm.MIND, TargetUnit.MINUTES, 10.0, reference = 60.0), 1e-9)
    }

    @Test
    fun `unmatched quests get absolute bounds`() {
        assertEquals(300.0, ProposalLimits.clamp(Realm.BODY, TargetUnit.REPS, 1_000.0, reference = null), 1e-9)
        assertEquals(120.0, ProposalLimits.clamp(Realm.BODY, TargetUnit.MINUTES, 300.0, reference = null), 1e-9)
        assertEquals(3.0, ProposalLimits.clamp(Realm.MIND, TargetUnit.OTHER, 3.0, reference = null), 1e-9)
        assertEquals(1.0, ProposalLimits.clamp(Realm.MIND, TargetUnit.OTHER, 0.0, reference = null), 1e-9)
    }

    @Test
    fun `sleep in hours never drops below seven`() {
        assertEquals(7.0, ProposalLimits.clamp(Realm.SELF, TargetUnit.HOURS, 6.0, null, isSleep = true), 1e-9)
    }

    @Test
    fun `titles match templates only when realm and unit agree`() {
        assertEquals("body_push_ups", TemplateMatcher.match("Push-ups", Realm.BODY, TargetUnit.REPS)?.key)
        assertNull(TemplateMatcher.match("Push-ups", Realm.BODY, TargetUnit.MINUTES))
        assertEquals("self_reading", TemplateMatcher.match("Read 15 pages", Realm.SELF, TargetUnit.PAGES)?.key)
        assertNull(TemplateMatcher.match("Read one arXiv paper", Realm.MIND, TargetUnit.OTHER))
        assertEquals("mind_deep_work", TemplateMatcher.match("Deep work on KPZ", Realm.MIND, TargetUnit.MINUTES)?.key)
    }

    @Test
    fun `sleep and muscle groups are recognised`() {
        assertTrue(TemplateMatcher.isSleep("Lights out by 23:00"))
        assertFalse(TemplateMatcher.isSleep("Read 15 pages"))
        assertEquals(MuscleGroup.LOWER, TemplateMatcher.muscleGroup("Squats 3×20"))
        assertEquals(MuscleGroup.NONE, TemplateMatcher.muscleGroup("Yoga"))
    }
}
