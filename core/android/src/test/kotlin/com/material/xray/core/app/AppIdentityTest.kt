package com.material.xray.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test
    fun `builds profile aware app keys`() {
        assertEquals(AppIdentity(profileId = 0, packageName = "com.example"), parseAppKey("com.example"))
        assertEquals(AppIdentity(profileId = 10, packageName = "com.example"), parseAppKey("10:com.example"))
        assertEquals("10:com.example", appKey(10, "com.example"))
    }

    @Test
    fun `calculates Android app uid ranges for each profile`() {
        assertEquals(10_000..99_999, appUidRangeForProfile(0))
        assertEquals(1_010_000..1_099_999, appUidRangeForProfile(10))
        assertEquals(1_001_234, uidForProfile(profileId = 10, uid = 1_234))
    }

    @Test
    fun `recognizes application uids across profiles`() {
        assertTrue(isApplicationUid(10_000))
        assertTrue(isApplicationUid(1_010_000))
        assertFalse(isApplicationUid(9_999))
        assertFalse(isApplicationUid(1_009_999))
    }
}
