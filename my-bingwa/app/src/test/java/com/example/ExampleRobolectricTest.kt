package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Pin to an SDK the installed Robolectric (4.16.1) actually ships shadows for.
// The app targets SDK 36, but Robolectric 4.16.1 has no SDK 36 sandbox yet and
// throws UnsupportedOperationException from DefaultSdkProvider when asked for it.
// Reading a string resource is SDK-independent, so a supported SDK is correct.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("My Bingwa", appName)
  }
}
