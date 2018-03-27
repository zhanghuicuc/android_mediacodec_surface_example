package com.example.zhanghui.decode2surfaceexample;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLPlayerActivity extends Activity implements SurfaceHolder.Callback{

    private static final String TAG = "GLPlayerActivity";
    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_STOPPED = 4;
    private static final int STATE_RELEASED = 5;

    private static final int VIDEORENDER_CREATED = 100;

    private GLSurfaceView mGLSurfaceV;
    private Surface mSurface;
    private SurfaceHolder mSurfaceHolder;
    private VideoRender mVideoRender;

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

    private Handler mainHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == VIDEORENDER_CREATED) {
                mSurface = new Surface(mVideoRender.getSurfaceTexture());
                new DecodeTask().execute(fileUrl);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        //getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
        //        WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_glplayer);

        mGLSurfaceV = new GLSurfaceView(this);
        mGLSurfaceV.setEGLContextClientVersion(2);
        mGLSurfaceV.getHolder().addCallback(this);
        ((FrameLayout) findViewById(R.id.root)).addView(mGLSurfaceV);
        mVideoRender = new VideoRender(this);
        mGLSurfaceV.setRenderer(mVideoRender);

        Intent intent = getIntent();
        fileUrl = intent.getStringExtra("fileurl");

        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        this.sendBroadcast(i);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.e(TAG, "surfaceholder surfaceCreated");
        this.mSurfaceHolder = holder;
        mSurfaceHolder.setKeepScreenOn(true);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (codec != null) {
            Log.e(TAG, "call surfaceDestroyed");
            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    Log.e(TAG, "call codec stop");
                    codec.stop();
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_STOPPED) {
                    Log.e(TAG, "call codec release");
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_RELEASED) {
                    extractor.release();
                }
                mSurface = null;
                mState = STATE_IDLE;
                return;
            }
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

    /**
     * A GLSurfaceView implementation that wraps TextureRender.  Used to render frames from a
     * video decoder to a View.
     */
    private class VideoRender
            implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private String TAG = "VideoRender";

        private TextureRender mTextureRender;
        private SurfaceTexture mSurfaceTexture;
        private boolean updateSurface = false;

        public VideoRender(Context context) {
            mTextureRender = new TextureRender();
        }

        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        public void onDrawFrame(GL10 glUnused) {
            Log.e(TAG, "onDrawFrame called");
            synchronized(this) {
                if (updateSurface) {
                    mSurfaceTexture.updateTexImage();
                    updateSurface = false;
                }
            }

            mTextureRender.drawFrame(mSurfaceTexture);
        }

        public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        }

        public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
            Log.e(TAG, "VideoRender surfaceCreated");
            mTextureRender.surfaceCreated();

            /*
             * Create the SurfaceTexture that will feed this textureID,
             * and pass it to the Player
             */
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());
            mSurfaceTexture.setOnFrameAvailableListener(this);

            Message msg = new Message();
            msg.what = VIDEORENDER_CREATED;
            mainHandler.sendMessage(msg);

            synchronized(this) {
                updateSurface = false;
            }
        }

        synchronized public void onFrameAvailable(SurfaceTexture surface) {
            Log.e(TAG, "onFrameAvailable called");
            updateSurface = true;
        }
    }  // End of class VideoRender.
}
