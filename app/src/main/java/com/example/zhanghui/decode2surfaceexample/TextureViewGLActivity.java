package com.example.zhanghui.decode2surfaceexample;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TextureViewGLActivity extends Activity {

    private static final String TAG = "TextureViewGLActivity";
    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_STOPPED = 4;
    private static final int STATE_RELEASED = 5;

    private static volatile boolean sReleaseInCallback = true;

    private TextureView mTextureV;
    private Renderer mRenderer;
    private Surface mSurface;

    private static final MediaCodecList sMCL = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    private String decoder = null;
    MediaExtractor extractor;
    MediaCodec codec;
    private Integer mState = STATE_IDLE;

    private final long kTimeOutUs = 5000; // 5ms timeout
    private boolean sawInputEOS = false;
    private boolean sawOutputEOS = false;
    private int deadDecoderCounter = 0;
    private int samplenum = 0;
    private int numframes = 0;
    private String fileUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_glplayer);

        // Start up the Renderer thread.  It'll sleep until the TextureView is ready.
        mRenderer = new Renderer();
        mRenderer.start();

        mTextureV = new TextureView(this);
        mTextureV.setSurfaceTextureListener(mRenderer);
        ((FrameLayout) findViewById(R.id.root)).addView(mTextureV);
    }

    /**
     * Handles GL rendering and SurfaceTexture callbacks.
     * <p>
     * We don't create a Looper, so the SurfaceTexture-by-way-of-TextureView callbacks
     * happen on the UI thread.
     */
    private static class Renderer extends Thread implements TextureView.SurfaceTextureListener {
        private Object mLock = new Object();        // guards mSurfaceTexture, mDone
        private SurfaceTexture mSurfaceTexture;
        private EglCore mEglCore;
        private boolean mDone;

        public Renderer() {
            super("TextureViewGL Renderer");
        }

        @Override
        public void run() {
            while (true) {
                SurfaceTexture surfaceTexture = null;

                // Latch the SurfaceTexture when it becomes available.  We have to wait for
                // the TextureView to create it.
                synchronized (mLock) {
                    while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            throw new RuntimeException(ie);     // not expected
                        }
                    }
                    if (mDone) {
                        break;
                    }
                }
                Log.d(TAG, "Got surfaceTexture=" + surfaceTexture);

                // Create an EGL surface for our new SurfaceTexture.  We're not on the same
                // thread as the SurfaceTexture, which is a concern for the *consumer*, which
                // wants to call updateTexImage().  Because we're the *producer*, i.e. the
                // one generating the frames, we don't need to worry about being on the same
                // thread.
                mEglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);
                WindowSurface windowSurface = new WindowSurface(mEglCore, mSurfaceTexture);
                windowSurface.makeCurrent();

                // Render frames until we're told to stop or the SurfaceTexture is destroyed.
                doAnimation(windowSurface);

                windowSurface.release();
                mEglCore.release();
                if (!sReleaseInCallback) {
                    Log.i(TAG, "Releasing SurfaceTexture in renderer thread");
                    surfaceTexture.release();
                }
            }

            Log.d(TAG, "Renderer thread exiting");
        }

        /**
         * Draws updates as fast as the system will allow.
         * <p>
         * In 4.4, with the synchronous buffer queue queue, the frame rate will be limited.
         * In previous (and future) releases, with the async queue, many of the frames we
         * render may be dropped.
         * <p>
         * The correct thing to do here is use Choreographer to schedule frame updates off
         * of vsync, but that's not nearly as much fun.
         */
        private void doAnimation(WindowSurface eglSurface) {
            final int BLOCK_WIDTH = 80;
            final int BLOCK_SPEED = 2;
            float clearColor = 0.0f;
            int xpos = -BLOCK_WIDTH / 2;
            int xdir = BLOCK_SPEED;
            int width = eglSurface.getWidth();
            int height = eglSurface.getHeight();

            Log.d(TAG, "Animating " + width + "x" + height + " EGL surface");

            while (true) {
                // Check to see if the TextureView's SurfaceTexture is still valid.
                synchronized (mLock) {
                    SurfaceTexture surfaceTexture = mSurfaceTexture;
                    if (surfaceTexture == null) {
                        Log.d(TAG, "doAnimation exiting");
                        return;
                    }
                }

                // Still alive, render a frame.
                GLES20.glClearColor(clearColor, clearColor, clearColor, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(xpos, height / 4, BLOCK_WIDTH, height / 2);
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                // Publish the frame.  If we overrun the consumer, frames will be dropped,
                // so on a sufficiently fast device the animation will run at faster than
                // the display refresh rate.
                //
                // If the SurfaceTexture has been destroyed, this will throw an exception.
                eglSurface.swapBuffers();

                // Advance state
                clearColor += 0.015625f;
                if (clearColor > 1.0f) {
                    clearColor = 0.0f;
                }
                xpos += xdir;
                if (xpos <= -BLOCK_WIDTH / 2 || xpos >= width - BLOCK_WIDTH / 2) {
                    Log.d(TAG, "change direction");
                    xdir = -xdir;
                }
            }
        }

        /**
         * Tells the thread to stop running.
         */
        public void halt() {
            synchronized (mLock) {
                mDone = true;
                mLock.notify();
            }
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            Log.d(TAG, "onSurfaceTextureAvailable(" + width + "x" + height + ")");
            synchronized (mLock) {
                mSurfaceTexture = st;
                mLock.notify();
            }
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
            // TODO: ?
        }

        @Override   // will be called on UI thread
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            Log.d(TAG, "onSurfaceTextureDestroyed");

            // We set the SurfaceTexture reference to null to tell the Renderer thread that
            // it needs to stop.  The renderer might be in the middle of drawing, so we want
            // to return false here so that the caller doesn't try to release the ST out
            // from under us.
            //
            // In theory.
            //
            // In 4.4, the buffer queue was changed to be synchronous, which means we block
            // in dequeueBuffer().  If the renderer has been running flat out and is currently
            // sleeping in eglSwapBuffers(), it's going to be stuck there until somebody
            // tears down the SurfaceTexture.  So we need to tear it down here to ensure
            // that the renderer thread will break.  If we don't, the thread sticks there
            // forever.
            //
            // The only down side to releasing it here is we'll get some complaints in logcat
            // when eglSwapBuffers() fails.
            synchronized (mLock) {
                mSurfaceTexture = null;
            }
            if (sReleaseInCallback) {
                Log.i(TAG, "Allowing TextureView to release SurfaceTexture");
            }
            return sReleaseInCallback;
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            //Log.d(TAG, "onSurfaceTextureUpdated");
        }
    }

    public class DecodeTask extends AsyncTask<String, Integer, String> {

        @Override
        protected String doInBackground(String... url) {
            DecodeVideo(url[0]);
            return url[0];
        }

        @Override
        protected void onPostExecute(String s) {
        }
    }

    private void DecodeVideo(String fileUrl){
        mState = STATE_IDLE;
        extractor = new MediaExtractor();
        codec = null;
        synchronized (mState) {
            mState = STATE_PREPARING;
        }
        if (!canDecodeVideo(fileUrl, extractor)) {
            Log.i(TAG, "no supported decoder found ");
            return; //skip
        }
        int trackIndex = extractor.getSampleTrackIndex();
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        try {
            codec = MediaCodec.createByCodecName(decoder);
        } catch (IOException e) {
            Log.e(TAG, "failed to create decoder");
            return;
        }
        Log.i("@@@@", "using codec: " + codec.getName());
        codec.configure(format, mSurface, null /* crypto */, 0 /* flags */);
        codec.start();
        synchronized (mState) {
            mState = STATE_PLAYING;
        }

        long decodeStartTime = SystemClock.elapsedRealtime();
        doSomeWork();
        long decodeEndTime = SystemClock.elapsedRealtime();

        synchronized (mState) {
            if (mState == STATE_PLAYING) {
                codec.stop();
                mState = STATE_STOPPED;
                codec.release();
                mState = STATE_RELEASED;
                extractor.release();
                mState = STATE_IDLE;
            }
        }
        return;
    }

    private void doSomeWork() {
        // start decode loop
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        final long kTimeOutUs = 5000; // 5ms timeout
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int deadDecoderCounter = 0;
        int samplenum = 0;
        int numframes = 0;
        while (!sawOutputEOS && deadDecoderCounter < 100 && mState == STATE_PLAYING) {
            // handle input
            Trace.beginSection("DecodeVideo handleinput");
            if (!sawInputEOS) {
                synchronized (mState) {
                    if (mState == STATE_PLAYING) {
                        int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);

                        if (inputBufIndex >= 0) {
                            ByteBuffer dstBuf = codec.getInputBuffer(inputBufIndex);

                            int sampleSize =
                                    extractor.readSampleData(dstBuf, 0 /* offset */);
                            long presentationTimeUs = extractor.getSampleTime();
                            Log.i("@@@@", "read sample " + samplenum + ":" + extractor.getSampleFlags()
                                    + " @ " + extractor.getSampleTime() + " size " + sampleSize);

                            if (sampleSize < 0) {
                                Log.d(TAG, "saw input EOS.");
                                sawInputEOS = true;
                                sampleSize = 0; // required otherwise queueInputBuffer returns invalid.
                            } else {
                                samplenum++; // increment before comparing with stopAtSample
                                if (samplenum == -1) {
                                    Log.d(TAG, "saw input EOS (stop at sample).");
                                    sawInputEOS = true; // tag this sample as EOS
                                }
                            }
                            codec.queueInputBuffer(
                                    inputBufIndex,
                                    0 /* offset */,
                                    sampleSize,
                                    presentationTimeUs,
                                    sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                            if (!sawInputEOS) {
                                extractor.advance();
                            }
                        }
                    }
                }
            }
            Trace.endSection();

            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    // handle output
                    int outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);

                    deadDecoderCounter++;
                    Trace.beginSection("DecodeVideo handleoutput");
                    if (outputBufIndex >= 0) {
                        if (info.size > 0) { // Disregard 0-sized buffers at the end.
                            deadDecoderCounter = 0;
                            numframes++;
                            Log.d(TAG, "got frame, size " + info.size + "/" + info.presentationTimeUs +
                                    "/" + numframes + "/" + info.flags);
                        }
                        codec.releaseOutputBuffer(outputBufIndex, true /* render */);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "saw output EOS.");
                            sawOutputEOS = true;
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            /*MediaFormat oformat = codec.getOutputFormat();
                            if (oformat.containsKey(MediaFormat.KEY_COLOR_FORMAT) &&
                                    oformat.containsKey(MediaFormat.KEY_WIDTH) &&
                                    oformat.containsKey(MediaFormat.KEY_HEIGHT)) {
                                int colorFormat = oformat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                                int width = oformat.getInteger(MediaFormat.KEY_WIDTH);
                                int height = oformat.getInteger(MediaFormat.KEY_HEIGHT);
                                Message msg = new Message();
                                msg.what = FORMAT_RESOLUTION_GOT;
                                msg.arg1 = width;
                                msg.arg2 = height;
                                mainHandler.sendMessage(msg);
                                Log.d(TAG, "output fmt: " + colorFormat + " dim " + width + "x" + height);
                            }*/
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "no output frame available yet");
                    }
                }
            }
            Trace.endSection();
        }
    }

    private boolean canDecodeVideo(String fileUrl, MediaExtractor ex) {
        try {
            ex.setDataSource(fileUrl);
            for (int i = 0; i < ex.getTrackCount(); ++i) {
                MediaFormat format = ex.getTrackFormat(i); //ex. call MPEG4Extractor.getTrackMetaData
                // only check for video codecs
                String mime = format.getString(MediaFormat.KEY_MIME).toLowerCase();
                if (!mime.startsWith("video/")) {
                    continue;
                }
                decoder = sMCL.findDecoderForFormat(format);
                if (decoder != null) {
                    ex.selectTrack(i);
                    return true;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "could not open path " + fileUrl);
        }
        return false;
    }

}
