package com.cyandev.test;

import com.cyandev.DiskLruCache;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Simple unit test for {@link DiskLruCache}.
 */
public class DiskLruCacheTest {

    @Rule
    public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private static final String CACHE_PATH = "cache";

    private Logger mLogger = Logger.getGlobal();
    private DiskLruCache mCache;

    @Before
    public void initialize() throws Exception {
        File cacheDir = mTemporaryFolder.newFolder(CACHE_PATH);
        mLogger.info("cacheDir=" + cacheDir);

        mCache = DiskLruCache.open(cacheDir, 1, 1, 6);
    }

    @Test
    public void addEntries() throws Exception {
        DiskLruCache.Editor editor = mCache.edit("1");
        assertTrue(inflateStream(editor.newOutputStream(0), "Foo"));
        editor.commit();

        editor = mCache.edit("2");
        assertTrue(inflateStream(editor.newOutputStream(0), "Bar"));
        editor.commit();

        editor = mCache.edit("3");
        assertTrue(inflateStream(editor.newOutputStream(0), "Baz"));
        editor.abort();

        assertEquals("Foo", mCache.get("1").getString(0));
        assertEquals("Bar", mCache.get("2").getString(0));
        assertNull(mCache.get("3"));
    }

    @Test
    public void trim() throws Exception {
        DiskLruCache.Editor editor = mCache.edit("1");
        assertTrue(inflateStream(editor.newOutputStream(0), "Foo"));
        editor.commit();

        editor = mCache.edit("2");
        assertTrue(inflateStream(editor.newOutputStream(0), "Bar"));
        editor.commit();

        // Access item "1".
        mCache.get("1");

        // When added something made max size exceeded.
        editor = mCache.edit("3");
        assertTrue(inflateStream(editor.newOutputStream(0), "Baz"));
        editor.commit();

        // Sleep for 1s to wait cleanup to be done.
        Thread.sleep(1000);

        // Then least recently used item should be removed.
        assertNotNull(mCache.get("1"));
        assertNull(mCache.get("2"));
        assertNotNull(mCache.get("3"));
    }

    private boolean inflateStream(OutputStream stream, String content) {
        try {
            OutputStreamWriter writer = new OutputStreamWriter(stream);
            writer.write(content);
            writer.flush();

            stream.close();

            return true;
        } catch (IOException e) {
            return false;
        }
    }

}
