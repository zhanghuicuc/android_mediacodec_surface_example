package com.example.zhanghui.decode2surfaceexample;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private EditText mUrlEditText;
    private Button mPlayButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mUrlEditText = (EditText) findViewById(R.id.input_url_editText);
        mPlayButton = (Button) findViewById(R.id.play_button);

        mPlayButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                String fileUrl = mUrlEditText.getText().toString().trim();
                fileUrl = "/sdcard/taipei-1080-4m.mp4";
                if (fileUrl == null) {
                    Toast.makeText(MainActivity.this, "file url wrong", Toast.LENGTH_SHORT).show();
                } else {
                    /*
                    * in PlayerActivity, we play video with SurfaceView
                    * in GLPlayerActivity, we play video with GLSurfaceView
                    * in TextureViewActivity, we play video with TextureView
                    * */
                    Intent intent = new Intent(getApplicationContext(), GLPlayerActivity.class);
                    intent.putExtra("fileurl", fileUrl);
                    startActivity(intent);
                }
            }
        });
    }
}
