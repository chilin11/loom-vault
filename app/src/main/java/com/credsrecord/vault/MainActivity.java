package com.credsrecord.vault;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.text.*;
import android.text.method.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.credsrecord.vault.core.SyncClient;
import com.credsrecord.vault.core.BiometricUnlock;
import com.credsrecord.vault.core.VaultStore;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG=0xFF101A1D, PANEL=0xFF1A282C, BORDER=0xFF34474B, TEXT=0xFFF1F5F2, MUTED=0xFFACBEBB, ACCENT=0xFFC5E8A5, WARM=0xFFFFB4AB;
    private static final String ADD_ENTRY="com.credsrecord.vault.ADD_ENTRY", PENDING_ADD="pendingQuickAdd", BIO_OFFER_DONE="biometricOfferDone";
    private boolean pendingQuickAdd, foreground, autoBiometricAttempted, biometricInFlight;
    private int gateGeneration, autoBiometricScheduledGeneration=-1;
    private static final java.util.concurrent.atomic.AtomicBoolean VAULT_WRITE_BUSY=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ArrayList<AlertDialog> dialogs=new ArrayList<>();
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private VaultStore store;
    private String master, token, serverUrl;
    private int revision=0;
    private boolean dirty=false, saving=false, reconciliationPending=false;
    private JSONArray entries=new JSONArray();
    private LinearLayout page, list;
    private EditText search;
    private final int EXPORT=110, IMPORT=111;

    @Override public void onDestroy(){io.shutdown();super.onDestroy();}
    @Override public void onCreate(Bundle b){setTheme(android.R.style.Theme_Material_NoActionBar);super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(0);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);store=new VaultStore(this);pendingQuickAdd=b!=null&&b.getBoolean(PENDING_ADD,false);autoBiometricAttempted=b!=null&&b.getBoolean("bioAttempted",false);if(b==null)consumeQuickAdd(getIntent());else if(getIntent()!=null)getIntent().setAction(null);showGate();}
    @Override protected void onStart(){super.onStart();foreground=true;scheduleAutoBiometric();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);consumeQuickAdd(intent);if(master==null)showGate();else openPendingAdd();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean(PENDING_ADD,pendingQuickAdd);state.putBoolean("bioAttempted",autoBiometricAttempted);super.onSaveInstanceState(state);}
    private void consumeQuickAdd(Intent intent){if(intent!=null&&ADD_ENTRY.equals(intent.getAction())){pendingQuickAdd=true;intent.setAction(null);}}
    private void openPendingAdd(){if(master==null||!pendingQuickAdd)return;if(saving||VAULT_WRITE_BUSY.get()){main.postDelayed(()->{if(!isDestroyed()&&foreground&&master!=null&&pendingQuickAdd)openPendingAdd();},250);return;}pendingQuickAdd=false;editDialog(-1);}
    @Override protected void onStop(){boolean wasUnlocked=master!=null;foreground=false;gateGeneration++;biometricInFlight=false;BiometricUnlock.cancel(this);for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();if(wasUnlocked){token=null;autoBiometricAttempted=false;}showGate();super.onStop();}
    private AlertDialog.Builder dialogBuilder(){return new AlertDialog.Builder(this){@Override public AlertDialog create(){AlertDialog dialog=super.create();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));return dialog;}};}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable outlined(int color,int radius){GradientDrawable d=shape(color,radius);d.setStroke(dp(1),BORDER);return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setFontFeatureSettings("kern");t.setLineSpacing(dp(3),1f);t.setSaveEnabled(false);if(bold)t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(0);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void pad(View v,int l,int t,int r,int b){v.setPadding(dp(l),dp(t),dp(r),dp(b));}
    private void add(LinearLayout p,View v,int width,int height){p.addView(v,new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height)));}
    private void gap(LinearLayout p,int h){add(p,new View(this),1,h);}
    private android.content.res.ColorStateList tint(int color){return android.content.res.ColorStateList.valueOf(color);}
    private RippleDrawable touch(int color,int radius){return new RippleDrawable(tint(0x26FFFFFF),outlined(color,radius),shape(Color.WHITE,radius));}
    private TextView action(String s,boolean primary){TextView b=text(s,15,primary?BG:TEXT,true);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(52));b.setBackground(touch(primary?ACCENT:PANEL,16));b.setFocusable(true);b.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});pad(b,16,12,16,12);return b;}
    private EditText field(String hint,String value,boolean secret){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);e.setContentDescription(hint);e.setText(value);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setSelectAllOnFocus(false);android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();GradientDrawable focus=outlined(PANEL,14);focus.setStroke(dp(2),ACCENT);states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},outlined(PANEL,14));e.setBackground(states);e.setMinHeight(dp(52));pad(e,15,12,15,12);e.setInputType(secret?129:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);return e;}
    private void label(LinearLayout box,String value){TextView t=text(value,13,MUTED,true);add(box,t,-1,-2);gap(box,8);}
    private LinearLayout.LayoutParams margins(int w,int h,int left,int top,int right,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(left),dp(top),dp(right),dp(bottom));return p;}
    private void surface(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(BG);scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()):null;int left=bars!=null?bars.left:insets.getSystemWindowInsetLeft(),right=bars!=null?bars.right:insets.getSystemWindowInsetRight(),top=bars!=null?bars.top:insets.getSystemWindowInsetTop(),bottom=bars!=null?Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom):insets.getSystemWindowInsetBottom();v.setPadding(left,top,right,bottom);return insets;});page=column();int side=Math.max(24,(int)(getResources().getConfiguration().screenWidthDp-600)/2);pad(page,side,24,side,32);scroll.addView(page);setContentView(scroll);scroll.requestApplyInsets();}
    private void header(LinearLayout box){LinearLayout brandRow=row();TextView mark=text("✦",23,ACCENT,true);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);add(brandRow,mark,32,-2);TextView brand=text("LOOM / 密语",13,ACCENT,true);brand.setLetterSpacing(.14f);add(brandRow,brand,-2,-2);add(box,brandRow,-1,-2);gap(box,24);}
    private void note(LinearLayout box,String title,String body){LinearLayout card=column();card.setBackground(outlined(PANEL,18));pad(card,18,18,18,18);add(card,text(title,14,ACCENT,true),-1,-2);gap(card,8);add(card,text(body,14,MUTED,false),-1,-2);box.addView(card,margins(-1,-2,0,0,0,0));}
    private void showGate(){gateGeneration++;if(biometricInFlight){biometricInFlight=false;BiometricUnlock.cancel(this);}master=null;entries=new JSONArray();list=null;search=null;surface();header(page);gap(page,20);add(page,text(store.exists()?"欢迎回来。":"给秘密一个\n安稳的地方。",32,TEXT,true),-1,-2);gap(page,12);add(page,text(pendingQuickAdd?"解锁后，即可安全添加一条新记录。":store.exists()?"可使用生物识别，也可输入主密码。":"创建主密码，让秘密只以密文留在设备上。",16,MUTED,false),-1,-2);gap(page,28);
        label(page,store.exists()?"主密码":"设置主密码 · 至少 10 位");EditText pass=field("输入主密码","",true);add(page,pass,-1,-2);gap(page,16);
        TextView open=action(store.exists()?"使用主密码解锁  →":"创建保险库  →",true);add(page,open,-1,-2);pass.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);pass.setOnEditorActionListener((v,id,event)->{if(id==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){if(open.isEnabled())open.performClick();return true;}return false;});
        if(store.exists()&&BiometricUnlock.isEnabled(this)&&BiometricUnlock.isAvailable(this)){gap(page,12);TextView biometric=action("◎  使用指纹 / 生物识别解锁",false);add(page,biometric,-1,-2);biometric.setOnClickListener(v->unlockBiometric());}
        open.setOnClickListener(v->{
            if(saving||VAULT_WRITE_BUSY.get()){toast("正在保存或恢复保险库，请稍候");return;}
            String p=pass.getText().toString();boolean creating=!store.exists();if(creating&&p.length()<10){pass.setError("主密码至少 10 位");return;}if(p.isEmpty()){pass.setError("请输入主密码");return;}
            if(creating&&!VAULT_WRITE_BUSY.compareAndSet(false,true)){toast("保险库正在写入，请稍候");return;}
            int attempt=gateGeneration;open.setEnabled(false);open.setAlpha(.65f);open.setText(creating?"正在创建保险库…":"正在安全解锁…");
            try{io.execute(()->{JSONObject data=null;Exception failure=null;try{if(creating){if(store.exists())throw new IOException("保险库已存在");data=new JSONObject().put("entries",new JSONArray());store.save(p,data);}else data=store.load(p);}catch(Exception ex){failure=ex;}finally{if(creating)VAULT_WRITE_BUSY.set(false);}JSONObject loaded=data;Exception error=failure;
                runOnUiThread(()->{if(isDestroyed()||attempt!=gateGeneration)return;if(error!=null){open.setEnabled(true);open.setAlpha(1f);open.setText("重试  →");pass.setError("主密码错误或保险库已变化，请重试");return;}if(!foreground||saving||VAULT_WRITE_BUSY.get()||master!=null)return;JSONArray found=loaded.optJSONArray("entries");if(found==null){pass.setError("保险库格式无效");return;}BiometricUnlock.cancel(this);biometricInFlight=false;master=p;entries=found;pass.setText("");((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(pass.getWindowToken(),0);showHome("");if(creating)offerBiometricAfterCreation();else openPendingAdd();});
            });}catch(RuntimeException ex){if(creating)VAULT_WRITE_BUSY.set(false);open.setEnabled(true);open.setAlpha(1f);toast("无法开始解锁，请重试");}
        });
        gap(page,26);note(page,"本地加密 · 私密守护","主密码不上传，也不会存储在服务器。请妥善保存：忘记后无法找回历史内容。");gap(page,16);TextView recover=action("已有加密备份？导入文件",false);add(page,recover,-1,-2);recover.setOnClickListener(v->pickImport());gap(page,12);TextView account=action("换手机？从账号恢复",false);add(page,account,-1,-2);account.setOnClickListener(v->accountDialog(true));scheduleAutoBiometric();
    }
    private void scheduleAutoBiometric(){if(!foreground||master!=null||autoBiometricAttempted||biometricInFlight||!store.exists()||!BiometricUnlock.isEnabled(this)||!BiometricUnlock.isAvailable(this))return;int generation=gateGeneration;if(autoBiometricScheduledGeneration==generation)return;autoBiometricScheduledGeneration=generation;main.postDelayed(()->{if(autoBiometricScheduledGeneration==generation)autoBiometricScheduledGeneration=-1;if(!foreground||isDestroyed()||master!=null||gateGeneration!=generation||autoBiometricAttempted)return;if(saving||VAULT_WRITE_BUSY.get()){main.postDelayed(()->{if(gateGeneration==generation)scheduleAutoBiometric();},500);return;}unlockBiometric();},350);}
    private void offerBiometricAfterCreation(){
        if(!foreground||master==null||getPreferences(0).getBoolean(BIO_OFFER_DONE,false)||!BiometricUnlock.isAvailable(this)){openPendingAdd();return;}
        dialogBuilder().setTitle("以后用指纹解锁？").setMessage("这台手机可使用系统生物识别快捷解锁。主密码仍是换机恢复的必需凭据。")
            .setNegativeButton("稍后",(d,w)->{getPreferences(0).edit().putBoolean(BIO_OFFER_DONE,true).apply();openPendingAdd();})
            .setPositiveButton("启用",(d,w)->{getPreferences(0).edit().putBoolean(BIO_OFFER_DONE,true).apply();enrollBiometric();}).show();
    }
    private void enrollBiometric(){if(master==null||saving||VAULT_WRITE_BUSY.get()||BiometricUnlock.isEnabled(this)||!BiometricUnlock.isAvailable(this))return;String p=master;BiometricUnlock.enroll(this,p,new BiometricUnlock.Callback(){@Override public void onSuccess(String ignored){if(foreground&&!isDestroyed()){toast("已开启；以后会优先使用生物识别解锁");openPendingAdd();}}@Override public void onError(String message){if(foreground&&!isDestroyed()){toast("未能启用，请使用主密码；可在工具箱重试");openPendingAdd();}}});}
    private void unlockBiometric(){
        if(!foreground||master!=null||!store.exists()||biometricInFlight||saving||VAULT_WRITE_BUSY.get())return;
        autoBiometricAttempted=true;biometricInFlight=true;int attempt=gateGeneration;
        BiometricUnlock.unlock(this,new BiometricUnlock.Callback(){
            @Override public void onSuccess(String passphrase){
                if(!foreground||isDestroyed()||attempt!=gateGeneration)return;
                io.execute(()->{try{JSONObject data=store.load(passphrase);JSONArray found=data.optJSONArray("entries");if(found==null)throw new IOException("保险库格式无效");runOnUiThread(()->{if(!foreground||isDestroyed()||attempt!=gateGeneration||saving||VAULT_WRITE_BUSY.get()||master!=null)return;biometricInFlight=false;master=passphrase;entries=found;showHome("");openPendingAdd();});}catch(Exception ex){runOnUiThread(()->{if(foreground&&!isDestroyed()&&attempt==gateGeneration){biometricInFlight=false;toast("生物识别凭据已失效，请使用主密码解锁并重新设置");}});}});
            }
            @Override public void onError(String message){if(foreground&&!isDestroyed()&&attempt==gateGeneration){biometricInFlight=false;toast("可输入主密码解锁，或手动点生物识别重试");}}
        });
    }
    private void biometricSettings(){
        if(master==null){showGate();return;}if(saving||VAULT_WRITE_BUSY.get()){toast("正在保存或恢复保险库，请稍候");return;}
        if(BiometricUnlock.isEnabled(this)){
            dialogBuilder().setTitle("关闭生物识别解锁？").setMessage("关闭后仍可用主密码解锁，本机保险库内容不会删除。")
                .setNegativeButton("取消",null).setPositiveButton("关闭",(d,w)->{try{BiometricUnlock.disable(this);toast("已关闭生物识别解锁");}catch(Exception ex){toast("关闭失败，请重试");}}).show();
            return;
        }
        if(!BiometricUnlock.isAvailable(this)){toast("当前设备没有可用的强生物识别；请先在系统设置中录入指纹等信息");return;}
        String unlockedMaster=master;
        dialogBuilder().setTitle("开启生物识别快捷解锁？")
            .setMessage("将主密码用此设备的 Android Keystore 加密保存，仅在通过系统生物识别后才能用于解锁。换机与恢复仍需记住主密码。")
            .setNegativeButton("取消",null)
            .setPositiveButton("继续",(d,w)->{if(master!=null&&master.equals(unlockedMaster))enrollBiometric();}).show();
    }
    private void showHome(String query){surface();header(page);LinearLayout hero=column();hero.setBackground(outlined(PANEL,24));pad(hero,22,22,22,22);add(hero,text("私人保险库",13,ACCENT,true),-1,-2);gap(hero,8);add(hero,text("安心收藏，随时取用。",26,TEXT,true),-1,-2);gap(hero,12);add(hero,text("本地加密保护  ·  "+entries.length()+" 条记录",14,MUTED,false),-1,-2);page.addView(hero,margins(-1,-2,0,0,0,0));gap(page,20);
        LinearLayout buttons=row();TextView addButton=action("＋  新建记录",true),more=action("工具箱",false);buttons.addView(addButton,new LinearLayout.LayoutParams(0,-2,1.5f));LinearLayout.LayoutParams toolsParams=new LinearLayout.LayoutParams(0,-2,1);toolsParams.setMarginStart(dp(12));buttons.addView(more,toolsParams);add(page,buttons,-1,-2);addButton.setOnClickListener(v->editDialog(-1));more.setOnClickListener(v->tools());gap(page,28);
        add(page,text("我的记录",21,TEXT,true),-1,-2);gap(page,12);search=field("搜索名称、账号或分类",query,false);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);add(page,search,-1,-2);gap(page,16);list=column();add(page,list,-1,-2);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render(s.toString());}public void afterTextChanged(Editable e){}});render(query);
    }
    private String display(JSONObject obj,String key,String fallback){String value=obj.optString(key).trim();return value.isEmpty()?fallback:value;}
    private void render(String query){if(list==null)return;list.removeAllViews();int count=0;for(int i=entries.length()-1;i>=0;i--){JSONObject obj=entries.optJSONObject(i);if(obj==null)continue;String hay=(obj.optString("title")+" "+obj.optString("username")+" "+obj.optString("category")).toLowerCase(Locale.ROOT);if(!hay.contains(query.toLowerCase(Locale.ROOT)))continue;count++;final int at=i;String title=display(obj,"title","未命名记录"),category=display(obj,"category","其他");LinearLayout card=row();card.setBackground(touch(PANEL,18));card.setFocusable(true);pad(card,16,18,16,18);TextView icon=text(title.substring(0,title.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT),19,ACCENT,true);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);icon.setGravity(Gravity.CENTER);icon.setBackground(shape(0xFF2D4238,14));card.addView(icon,margins(44,44,0,0,14,0));LinearLayout info=column();TextView name=text(title,17,TEXT,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);add(info,name,-1,-2);gap(info,5);TextView meta=text(category+" · "+display(obj,"username","未填写账号"),13,MUTED,false);meta.setMaxLines(1);meta.setEllipsize(TextUtils.TruncateAt.END);add(info,meta,-1,-2);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView chevron=text("›",26,MUTED,false);chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(chevron,margins(-2,-2,12,0,0,0));list.addView(card,margins(-1,-2,0,0,0,12));card.setContentDescription(title+"，"+category+"，查看详情");card.setOnClickListener(v->detail(at));}if(count==0){LinearLayout empty=column();empty.setBackground(outlined(PANEL,20));empty.setGravity(Gravity.CENTER);pad(empty,20,28,20,28);TextView mark=text("✧",36,ACCENT,false);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);add(empty,mark,-2,-2);gap(empty,12);TextView title=text(query.isEmpty()?"从第一条秘密开始":"没有找到相关记录",18,TEXT,true);title.setGravity(Gravity.CENTER);add(empty,title,-1,-2);gap(empty,8);TextView hint=text(query.isEmpty()?"账号、密码与重要备注，\n都可以安心收在这里。":"换个名称、账号或分类试试。",14,MUTED,false);hint.setGravity(Gravity.CENTER);add(empty,hint,-1,-2);gap(empty,20);TextView start=action(query.isEmpty()?"＋  添加第一条记录":"清除搜索",false);add(empty,start,-1,-2);start.setOnClickListener(v->{if(query.isEmpty())editDialog(-1);else search.setText("");});list.addView(empty,margins(-1,-2,0,0,0,0));}}
    private void detail(int index){if(master==null)return;JSONObject obj=entries.optJSONObject(index);if(obj==null)return;surface();TextView back=action("←  返回保险库",false);add(page,back,-1,-2);back.setOnClickListener(v->showHome(""));gap(page,24);add(page,text(display(obj,"category","其他"),13,ACCENT,true),-1,-2);gap(page,8);add(page,text(display(obj,"title","未命名记录"),30,TEXT,true),-1,-2);gap(page,24);
        item("账号",obj.optString("username"),true,false);item("密码",obj.optString("password"),true,true);item("网址 / 应用",obj.optString("url"),false,false);item("备注",obj.optString("notes"),false,false);gap(page,12);TextView edit=action("编辑记录",true);add(page,edit,-1,-2);edit.setOnClickListener(v->editDialog(index));gap(page,12);TextView del=action("删除记录",false);del.setTextColor(WARM);add(page,del,-1,-2);del.setOnClickListener(v->dialogBuilder().setTitle("删除这条记录？").setMessage("删除后会保存到本地保险库；如已登录，同步时也将删除云端副本。此操作不可撤销。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{JSONArray next=new JSONArray();for(int i=0;i<entries.length();i++)if(i!=index)next.put(entries.optJSONObject(i));commit(next,()->showHome(""));}).show());}
    private void item(String heading,String value,boolean canCopy,boolean secret){LinearLayout card=column();card.setBackground(outlined(PANEL,18));pad(card,18,16,18,16);add(card,text(heading,13,MUTED,true),-1,-2);gap(card,10);TextView content=text(value.isEmpty()?"未填写":secret?"••••••••••••":value,17,value.isEmpty()?MUTED:TEXT,false);content.setTextIsSelectable(!secret);if(secret)content.setTypeface(Typeface.MONOSPACE);add(card,content,-1,-2);if(!value.isEmpty()&&(secret||canCopy)){gap(card,12);LinearLayout controls=row();if(secret){final boolean[] visible={false};TextView reveal=action("显示密码",false);add(controls,reveal,-2,-2);reveal.setOnClickListener(v->{if(master==null)return;visible[0]=!visible[0];content.setText(visible[0]?value:"••••••••••••");reveal.setText(visible[0]?"隐藏密码":"显示密码");});}if(canCopy){TextView copy=action("复制"+heading,false);controls.addView(copy,margins(-2,-2,secret?8:0,0,0,0));copy.setOnClickListener(v->{if(master!=null)copy(value,secret);});}add(card,controls,-1,-2);}page.addView(card,margins(-1,-2,0,0,0,12));}
    private void copy(String value,boolean secret){android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=ClipData.newPlainText(secret?"密码":"账号",value);if(secret){android.os.PersistableBundle flags=new android.os.PersistableBundle();flags.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(flags);}cb.setPrimaryClip(clip);toast("已复制，60 秒后尝试清除剪贴板");main.postDelayed(()->{try{if(cb.hasPrimaryClip()&&cb.getPrimaryClip().getItemCount()>0&&value.equals(cb.getPrimaryClip().getItemAt(0).coerceToText(this).toString()))cb.setPrimaryClip(ClipData.newPlainText("",""));}catch(RuntimeException ignored){}},60000);}
    private void editDialog(int index){
        if(master==null){pendingQuickAdd=index<0;showGate();return;}
        if(saving){toast("请等待上一条记录保存完成");return;}
        JSONObject old=index<0?new JSONObject():entries.optJSONObject(index);
        if(old==null)return;
        ScrollView scroll=new ScrollView(this);
        LinearLayout form=column();
        pad(form,24,12,24,12);scroll.addView(form);
        add(form,text("记下网站别名和密码，即可加密保存。\n仅名称必填；账号和更多信息可稍后补充。",13,MUTED,false),-1,-2);
        gap(form,16);
        String[] keys={"title","password","username","category","url","notes"};
        String[] labels={"名称 / 网站别名 · 必填","密码","账号 · 选填","分类 · 选填","网址 / 应用 · 选填","备注 · 选填"};
        String[] hints={"例如：常用邮箱、购物网站","输入密码，也可留空","用户名、邮箱或手机号","例如：工作、生活","https:// 或应用名称","添加需要记住的细节"};
        EditText[] inputs=new EditText[keys.length];
        String[] initial=new String[keys.length];
        LinearLayout extras=column();
        TextView more=action("更多信息  ▾",false);
        boolean hasExtras=false;
        for(int i=3;i<keys.length;i++)hasExtras|=!old.optString(keys[i],"").isEmpty();
        extras.setVisibility(hasExtras?View.VISIBLE:View.GONE);
        more.setText(hasExtras?"收起更多信息  ▴":"更多信息  ▾");
        more.setContentDescription(hasExtras?"更多信息，已展开，点击收起":"更多信息，已折叠，点击填写分类、网址或备注");
        for(int i=0;i<keys.length;i++){
            if(i==3){add(form,more,-1,-2);add(form,extras,-1,-2);gap(extras,14);}
            LinearLayout parent=i<3?form:extras;
            label(parent,labels[i]);
            initial[i]=old.optString(keys[i],"");
            inputs[i]=field(hints[i],initial[i],i==1);
            inputs[i].setId(View.generateViewId());
            inputs[i].setContentDescription(labels[i]);
            if(i==2)inputs[i].setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            if(i==4)inputs[i].setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            inputs[i].setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|(i==0||i==1||i==3||i==4?android.view.inputmethod.EditorInfo.IME_ACTION_NEXT:android.view.inputmethod.EditorInfo.IME_ACTION_DONE));
            if(i==5){
                inputs[i].setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                inputs[i].setSingleLine(false);inputs[i].setMinLines(3);inputs[i].setGravity(Gravity.TOP);
                inputs[i].setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            }
            if(i==1){
                LinearLayout passwordRow=row();passwordRow.addView(inputs[i],new LinearLayout.LayoutParams(0,-2,1));
                TextView visibility=action("显示",false);visibility.setContentDescription("显示密码输入");
                passwordRow.addView(visibility,margins(76,52,8,0,0,0));
                visibility.setOnClickListener(v->{
                    boolean showing=inputs[1].getTransformationMethod() instanceof android.text.method.HideReturnsTransformationMethod;
                    int start=inputs[1].getSelectionStart(),end=inputs[1].getSelectionEnd();
                    inputs[1].setTransformationMethod(showing?android.text.method.PasswordTransformationMethod.getInstance():android.text.method.HideReturnsTransformationMethod.getInstance());
                    inputs[1].setSelection(Math.max(0,start),Math.max(0,end));
                    visibility.setText(showing?"显示":"隐藏");visibility.setContentDescription(showing?"显示密码输入":"隐藏密码输入");
                });
                add(parent,passwordRow,-1,-2);gap(parent,12);
            }else{add(parent,inputs[i],-1,-2);gap(parent,12);}
        }
        inputs[0].setNextFocusForwardId(inputs[1].getId());inputs[1].setNextFocusForwardId(inputs[2].getId());
        inputs[3].setNextFocusForwardId(inputs[4].getId());
        inputs[4].setNextFocusForwardId(inputs[5].getId());
        more.setOnClickListener(v->{
            boolean expand=extras.getVisibility()!=View.VISIBLE;
            if(!expand&&extras.hasFocus())more.requestFocus();
            extras.setVisibility(expand?View.VISIBLE:View.GONE);
            more.setText(expand?"收起更多信息  ▴":"更多信息  ▾");
            more.setContentDescription(expand?"更多信息，已展开，点击收起":"更多信息，已折叠，点击填写分类、网址或备注");
        });
        AlertDialog dialog=dialogBuilder().setTitle(index<0?"新建记录":"编辑记录").setView(scroll).setNegativeButton("取消",null).setPositiveButton("加密保存",null).create();
        dialog.setOnShowListener(d->{
            dialog.getWindow().setBackgroundDrawable(outlined(BG,24));
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getButton(-1).setTextColor(ACCENT);dialog.getButton(-2).setTextColor(MUTED);
            dialog.getButton(-1).setOnClickListener(v->{
                if(master==null){dialog.dismiss();return;}
                if(saving){toast("请等待保存完成");return;}
                if(inputs[0].getText().toString().trim().isEmpty()){
                    inputs[0].setError("请填写名称或网站别名");inputs[0].requestFocus();return;
                }
                try{
                    // Clone rather than rebuild: retain metadata and fields unknown to this form.
                    JSONObject item=new JSONObject(old.toString());
                    if(index<0){item.put("id",UUID.randomUUID().toString());item.put("updatedAt",System.currentTimeMillis());}
                    for(int i=0;i<keys.length;i++){
                        String value=inputs[i].getText().toString();
                        // Untouched values (including absent/null keys) must survive an edit verbatim.
                        if(index<0||!value.equals(initial[i]))item.put(keys[i],i==0?value.trim():value);
                    }
                    JSONArray next=new JSONArray();
                    for(int i=0;i<entries.length();i++)next.put(i==index?item:entries.getJSONObject(i));
                    if(index<0)next.put(item);
                    commit(next,()->{dialog.dismiss();showHome("");});
                }catch(Exception ex){toast("保存失败："+ex.getMessage());}
            });
            inputs[2].setOnEditorActionListener((view,actionId,event)->{
                if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){
                    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0);
                    return true;
                }
                return false;
            });
        });
        dialog.show();
        if(index<0){
            inputs[0].requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }
    private void commit(JSONArray next,Runnable after){String current=master;if(current==null||saving||!VAULT_WRITE_BUSY.compareAndSet(false,true)){toast("请先解锁或等待保存完成");return;}saving=true;try{io.execute(()->{try{store.save(current,new JSONObject().put("entries",next));runOnUiThread(()->{if(isDestroyed())return;saving=false;dirty=true;if(master!=null&&master.equals(current)){entries=next;after.run();toast("已加密保存到本机");openPendingAdd();if(token!=null&&!reconciliationPending)sync(false);}});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;saving=false;toast("保存失败："+e.getMessage());});}finally{VAULT_WRITE_BUSY.set(false);}});}catch(RuntimeException e){saving=false;VAULT_WRITE_BUSY.set(false);toast("无法开始保存，请重试");}}
    private void tools(){String state=token==null?"未登录":"已登录 · 可同步";String bio=BiometricUnlock.isEnabled(this)?"◎  关闭生物识别解锁":"◎  开启生物识别解锁";dialogBuilder().setTitle("工具箱  /  " +state).setItems(new String[]{bio,"☁  登录 / 注册与同步","↻  立即同步","⇧  导出加密备份","⇩  导入加密备份","◯  锁定保险库"},(d,which)->{switch(which){case 0:biometricSettings();break;case 1:accountDialog(false);break;case 2:sync(true);break;case 3:exportFile();break;case 4:pickImport();break;case 5:token=null;master=null;autoBiometricAttempted=false;showGate();break;}}).show();}
    private void accountDialog(boolean recovery){if(!foreground||isDestroyed())return;LinearLayout form=column();pad(form,20,8,20,4);label(form,"服务器地址（生产环境必须 HTTPS）");EditText url=field("https://your-server.example",getPreferences(0).getString("serverUrl",""),false);add(form,url,-1,50);gap(form,13);label(form,"账号");EditText user=field("输入用户名","",false);add(form,user,-1,50);gap(form,13);label(form,"登录密码（不是主密码）");EditText pass=field("输入登录密码","",true);add(form,pass,-1,50);gap(form,15);add(form,text("登录密码用于身份验证；主密码只用于解密，服务器无法读取内容。",12,MUTED,false),-1,-2);ScrollView scroll=new ScrollView(this);scroll.addView(form);dialogBuilder().setTitle(recovery?"从账号恢复":"账号与云同步").setView(scroll).setNegativeButton("取消",null).setNeutralButton("注册",(d,w)->authenticate(url.getText().toString(),user.getText().toString(),pass.getText().toString(),true,recovery)).setPositiveButton("登录",(d,w)->authenticate(url.getText().toString(),user.getText().toString(),pass.getText().toString(),false,recovery)).show();}
    private void authenticate(String url,String user,String password,boolean register,boolean recovery){if(user.trim().length()<3||password.getBytes(StandardCharsets.UTF_8).length<8){toast("账号至少 3 位，登录密码至少 8 字节");return;}getPreferences(0).edit().putString("serverUrl",url.trim()).apply();io.execute(()->{try{SyncClient client=new SyncClient(url.trim());JSONObject res=register?client.register(user.trim(),password):client.login(user.trim(),password);String t=res.getString("token");int rev=res.optInt("revision");String remote=res.optString("vault","");String local=store.exists()?store.exportEnvelope():"";runOnUiThread(()->{if(!foreground||isDestroyed())return;token=t;serverUrl=url.trim();revision=rev;boolean hasRemote=!remote.isEmpty()&&!remote.equals("null");reconciliationPending=hasRemote&&!remote.equals(local);if(!hasRemote){toast("账号已连接；解锁后同步本机密文");if(master!=null)sync(false);else showGate();}else if(remote.equals(local)){reconciliationPending=false;toast("云端与本机一致，已连接");if(master==null)showGate();}else if(master==null || recovery || local.isEmpty()){askRestore(remote,rev);}else{dialogBuilder().setTitle("云端已有保险库").setMessage("请选择保留哪份数据。未选择之前不会自动上传。建议先导出本机备份。") .setNegativeButton("稍后处理",null).setNeutralButton("下载云端",(d,w)->askRestore(remote,rev)).setPositiveButton("上传本机",(d,w)->uploadCurrent(rev)).show();}});}catch(Exception e){runOnUiThread(()->{if(foreground&&!isDestroyed())toast("连接失败："+e.getMessage());});}});}
    private void askRestore(String remote,int rev){if(!foreground||isDestroyed())return;EditText pass=field("云端保险库的主密码","",true);dialogBuilder().setTitle("验证并恢复云端保险库").setMessage("请先确认主密码。验证成功后才会替换本机内容；替换前的加密副本保存在应用内部。") .setView(pass).setNegativeButton("取消",null).setPositiveButton("验证并恢复",(d,w)->replaceEnvelope(remote,pass.getText().toString(),rev)).show();}
    private void replaceEnvelope(String envelope,String pass,int rev){
        if(saving||!VAULT_WRITE_BUSY.compareAndSet(false,true)){toast("请等待保存或恢复完成");return;}
        saving=true;BiometricUnlock.cancel(this);showGate();
        try{io.execute(()->{
            boolean success=false;
            try{
                JSONObject verified=VaultStore.decryptEnvelope(pass,envelope);
                if(verified.optJSONArray("entries")==null)throw new IOException("保险库缺少记录列表");
                if(store.exists()){
                    String previous=store.exportEnvelope();
                    try(FileOutputStream backup=openFileOutput("vault-before-restore.envelope",MODE_PRIVATE)){
                        backup.write(previous.getBytes(StandardCharsets.UTF_8));backup.getFD().sync();
                    }
                }
                BiometricUnlock.disable(this);
                store.importEnvelope(envelope);
                success=true;
            }catch(Exception ignored){/* Keep the existing vault if verification or replacement fails. */}
            finally{VAULT_WRITE_BUSY.set(false);}
            final boolean restored=success;
            runOnUiThread(()->{if(isDestroyed())return;saving=false;if(restored){revision=rev;dirty=false;reconciliationPending=rev<0;}showGate();toast(restored?"恢复成功；旧生物识别已清除，请用备份主密码解锁后重新启用":"未替换本机：验证或保存失败，请用主密码重试");});
        });}catch(RuntimeException e){VAULT_WRITE_BUSY.set(false);saving=false;showGate();toast("无法开始恢复，请重试");}
    }
    private void sync(boolean interactive){if(token==null||serverUrl==null){if(interactive)accountDialog(false);return;}io.execute(()->{try{SyncClient client=new SyncClient(serverUrl);JSONObject state=client.getVault(token);int remoteRevision=state.getInt("revision");String remote=state.optString("vault","");String local=store.exists()?store.exportEnvelope():"";boolean hasRemote=!remote.isEmpty()&&!remote.equals("null");if(hasRemote && !remote.equals(local) && (remoteRevision!=revision||reconciliationPending)){runOnUiThread(()->{if(!foreground||isDestroyed())return;reconciliationPending=true;dialogBuilder().setTitle("发现同步冲突").setMessage("云端与本机内容不同。请先导出备份，再手动选择保留哪份。") .setNegativeButton("稍后处理",null).setNeutralButton("下载云端",(d,w)->askRestore(remote,remoteRevision)).setPositiveButton("上传本机",(d,w)->uploadCurrent(remoteRevision)).show();});return;}if(master==null){runOnUiThread(()->{if(interactive&&foreground&&!isDestroyed())toast("请先解锁保险库");});return;}if(!hasRemote||!remote.equals(local)){JSONObject uploaded=client.putVault(token,remoteRevision,local);int result=uploaded.getInt("revision");runOnUiThread(()->{revision=result;dirty=false;reconciliationPending=false;if(interactive&&foreground&&!isDestroyed())toast("密文已同步");});}else runOnUiThread(()->{revision=remoteRevision;dirty=false;reconciliationPending=false;if(interactive&&foreground&&!isDestroyed())toast("已是最新版本");});}catch(SyncClient.ConflictException c){runOnUiThread(()->{reconciliationPending=true;if(interactive&&foreground&&!isDestroyed()){toast("云端版本已更新，请重新同步处理冲突");sync(true);}});}catch(Exception e){runOnUiThread(()->{if(interactive&&foreground&&!isDestroyed())toast("同步失败："+e.getMessage());});}});}
    private void uploadCurrent(int remoteRevision){if(master==null||saving){toast("请先解锁并等待保存完成");return;}io.execute(()->{try{String encrypted=store.exportEnvelope();JSONObject response=new SyncClient(serverUrl).putVault(token,remoteRevision,encrypted);int newRevision=response.getInt("revision");runOnUiThread(()->{revision=newRevision;dirty=false;reconciliationPending=false;toast("已上传本机密文");});}catch(Exception e){runOnUiThread(()->toast("上传失败："+e.getMessage()));}});}
    private void exportFile(){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"loom-vault-"+new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date())+".vault.json");startActivityForResult(i,EXPORT);}catch(Exception e){toast("无法打开文件选择器");}}
    private void pickImport(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,IMPORT);}catch(Exception e){toast("无法打开文件选择器");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==EXPORT){io.execute(()->{try{String encrypted=store.exportEnvelope();try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入文件");out.write(encrypted.getBytes(StandardCharsets.UTF_8));}runOnUiThread(()->{if(foreground&&!isDestroyed())toast("加密备份已导出");});}catch(Exception e){runOnUiThread(()->{if(foreground&&!isDestroyed())toast("导出失败："+e.getMessage());});}});}else if(request==IMPORT){io.execute(()->{try{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取文件");byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>2_500_000)throw new IOException("文件过大");}}String envelope=out.toString("UTF-8");runOnUiThread(()->{if(!foreground||isDestroyed())return;EditText pass=field("备份的主密码","",true);dialogBuilder().setTitle("验证并导入备份").setMessage("验证成功才会替换本机内容；先前的加密文件将在应用内部保留一个副本。") .setView(pass).setNegativeButton("取消",null).setPositiveButton("验证并导入",(d,w)->replaceEnvelope(envelope,pass.getText().toString(),-1)).show();});}catch(Exception e){runOnUiThread(()->{if(foreground&&!isDestroyed())toast("导入失败："+e.getMessage());});}});}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(master!=null)showHome("");else super.onBackPressed();}
}
