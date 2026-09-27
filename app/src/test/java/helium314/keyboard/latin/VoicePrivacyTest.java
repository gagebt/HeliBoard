package helium314.keyboard.latin;
import dev.notune.transcribe.*;
import helium314.keyboard.*;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.settings.Settings;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, shadows={ShadowLocaleManagerCompat.class, ShadowInputMethodManager2.class,
 ShadowInputMethodService.class, ShadowKeyboardSwitcher.class, ShadowHandler.class, ShadowFacilitator2.class})
public class VoicePrivacyTest {
 private static Object field(Object o,String n) throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o); }
 private LatinIME ime() throws Exception { InputLogicTest fixture=new InputLogicTest();fixture.reset();return (LatinIME)field(fixture,"latinIME"); }
 private void startField(LatinIME ime) { EditorInfo info=new EditorInfo();info.packageName="audit.app";info.fieldId=7;info.inputType=InputType.TYPE_CLASS_TEXT;ime.onStartInput(info,false); }
 private RustInputMethodService.Host host(LatinIME ime) throws Exception { return (RustInputMethodService.Host)field(field(ime,"mVoiceController"),"host"); }
 @Test public void incognitoPresentBeforeFieldIsObserved() throws Exception {
  LatinIME ime=ime(); Settings.getInstance().toggleAlwaysIncognitoMode();
  assertTrue(Settings.getValues().mIncognitoModeEnabled); startField(ime);
  assertTrue("Control: Incognito set before binding must be private",host(ime).currentEditor().privateField);
 }
 @Test public void toolbarIncognitoMustReachVoiceInTheCurrentField() throws Exception {
  LatinIME ime=ime();startField(ime); assertFalse(host(ime).currentEditor().privateField);
  ime.mKeyboardActionListener.onCodeInput(KeyCode.TOGGLE_INCOGNITO_MODE,0,0,false);
  assertTrue("User-facing Incognito switch is on",Settings.getValues().mIncognitoModeEnabled);
  assertTrue("Voice still sees a public editor after the live Incognito toggle",host(ime).currentEditor().privateField);
 }
}
