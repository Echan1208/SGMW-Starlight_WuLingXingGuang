package com.sgmwstarlight.pwoff;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Opening the app (re)starts the guard and immediately runs one check. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GuardService.start(this, "manual");
        Toast.makeText(this, "行人警示自动关闭：已启动", Toast.LENGTH_SHORT).show();
        finish();
    }
}
