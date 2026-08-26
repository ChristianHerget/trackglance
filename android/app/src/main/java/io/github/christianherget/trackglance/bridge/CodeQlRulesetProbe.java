package io.github.christianherget.trackglance.bridge;

import android.app.Activity;
import android.os.Bundle;
import java.io.IOException;

/** Deliberately unsafe source-to-sink flow used only by the disposable ruleset probe. */
public final class CodeQlRulesetProbe extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String command = getIntent().getStringExtra("command");
        if (command == null) {
            return;
        }
        try {
            Runtime.getRuntime().exec(command);
        } catch (IOException ignored) {
            // The probe is analyzed, never executed or merged.
        }
    }
}
