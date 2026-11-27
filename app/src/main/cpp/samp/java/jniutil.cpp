#include "jniutil.h"
#include "game/game.h"
extern CGame *pGame;

CJavaWrapper::CJavaWrapper(JNIEnv *env, jobject activity)
{
    this->activity = env->NewGlobalRef(activity);

    jclass clas = env->GetObjectClass(activity);
    if(!clas)
    {
        FLog("no clas");
        return;
    }

    s_showTab = env->GetMethodID(clas, "showTab", "()V");
    s_hideTab = env->GetMethodID(clas, "hideTab", "()V");
    s_clearTab = env->GetMethodID(clas, "clearTab", "()V");
    s_setTab = env->GetMethodID(clas, "setTab", "(ILjava/lang/String;II)V");

    s_showLoadingScreen = env->GetMethodID(clas, "showLoadingScreen", "()V");
    s_hideLoadingScreen = env->GetMethodID(clas, "hideLoadingScreen", "()V");

    s_setPauseState = env->GetMethodID(clas, "setPauseState", "(Z)V");
    
    s_ShowDialog = env->GetMethodID(clas, "showDialog", "(II[B[B[B[B)V");

	s_showInputLayout = env->GetMethodID(clas, "showKeyboard", "()V");
    s_hideInputLayout = env->GetMethodID(clas, "hideKeyboard", "()V");

    s_exitGame = env->GetMethodID(clas, "exitGame", "()V");

    s_showEditObject = env->GetMethodID(clas, "showEditObject", "()V");
    s_hideEditObject = env->GetMethodID(clas, "hideEditObject", "()V");
    s_downloadCustomModel = env->GetMethodID(clas, "downloadCustomModel", "(IILjava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        s_downloadCustomModel = nullptr;
    }

    env->DeleteLocalRef(clas);
}

void CJavaWrapper::ShowKeyboard()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_showInputLayout);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::HideKeyboard()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_hideInputLayout);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::ShowLoadingScreen()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_showLoadingScreen);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::HideLoadingScreen()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_hideLoadingScreen);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::SetPauseState(bool pause)
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_setPauseState, pause);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::SetTab(int id, char* names, int score, int pings)
{
    JNIEnv* global_env;
    javaVM->GetEnv((void**)&global_env, JNI_VERSION_1_6);

	if (!global_env)
	{
		LOGI("No env");
		return;
	}

	jclass strClass = global_env->FindClass("java/lang/String"); 
	jmethodID ctorID = global_env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V"); 
	jstring encoding = global_env->NewStringUTF("UTF-8"); 

	jbyteArray bytes = global_env->NewByteArray(strlen(names)); 
	global_env->SetByteArrayRegion(bytes, 0, strlen(names), (jbyte*)names); 
	jstring str1 = (jstring)global_env->NewObject(strClass, ctorID, bytes, encoding);

    global_env->CallVoidMethod(activity, s_setTab, id, str1, score, pings);

    EXCEPTION_CHECK(global_env);
}

void CJavaWrapper::ShowTab()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_showTab);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::HideTab()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_hideTab);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::ClearTab()
{
    JNIEnv* p;
    javaVM->GetEnv((void**)&p, JNI_VERSION_1_6);
    p->CallVoidMethod(activity, s_clearTab);
    EXCEPTION_CHECK(p);
}

void CJavaWrapper::ShowDialog(int dialogStyle, int dialogID, char* title, char* text, char* button1, char* button2)
{
	if (!javaVM || !activity || !s_ShowDialog) return;

	JNIEnv* env = nullptr;
	bool attached = false;
	jint envStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
	if (envStat == JNI_EDETACHED)
	{
		if (javaVM->AttachCurrentThread(&env, nullptr) != 0 || !env)
		{
			FLog("ShowDialog: AttachCurrentThread failed");
			return;
		}
		attached = true;
	}
	else if (envStat != JNI_OK || !env)
	{
		FLog("ShowDialog: No env");
		return;
	}

	std::string sTitle(title ? title : "");
	std::string sText(text ? text : "");
	std::string sButton1(button1 ? button1 : "");
	std::string sButton2(button2 ? button2 : "");

	auto makeByteArray = [env](const std::string& s) -> jbyteArray {
		jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.length()));
		if (arr && !s.empty())
		{
			env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.length()),
				reinterpret_cast<const jbyte*>(s.data()));
		}
		return arr;
	};

	jbyteArray jstrTitle = makeByteArray(sTitle);
	jbyteArray jstrText = makeByteArray(sText);
	jbyteArray jstrButton1 = makeByteArray(sButton1);
	jbyteArray jstrButton2 = makeByteArray(sButton2);

	if (jstrTitle && jstrText && jstrButton1 && jstrButton2)
	{
		env->CallVoidMethod(activity, s_ShowDialog, dialogID, dialogStyle, jstrTitle, jstrText, jstrButton1, jstrButton2);
		if (env->ExceptionCheck())
		{
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
	}

	if (jstrTitle) env->DeleteLocalRef(jstrTitle);
	if (jstrText) env->DeleteLocalRef(jstrText);
	if (jstrButton1) env->DeleteLocalRef(jstrButton1);
	if (jstrButton2) env->DeleteLocalRef(jstrButton2);

	if (attached)
	{
		javaVM->DetachCurrentThread();
	}
}

void CJavaWrapper::exitGame() {

    JNIEnv* env;
    javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (!env)
    {
        FLog("No env");
        return;
    }

    env->CallVoidMethod(this->activity, this->s_exitGame);
}

void CJavaWrapper::ShowEditObject() {

    JNIEnv* env;
    javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (!env)
    {
        FLog("No env");
        return;
    }

    env->CallVoidMethod(this->activity, this->s_showEditObject);
}

void CJavaWrapper::HideEditObject() {

    JNIEnv* env;
    javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (!env)
    {
        FLog("No env");
        return;
    }

    env->CallVoidMethod(this->activity, this->s_hideEditObject);
}

bool CJavaWrapper::DownloadCustomModel(int fileType, uint32_t crc, const char* url, const char* destPath) {
    if (!s_downloadCustomModel || !url || !destPath || !javaVM) {
        return false;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint envStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0 || !env) {
            return false;
        }
        attached = true;
    } else if (envStat != JNI_OK || !env) {
        return false;
    }

    jstring jUrl = env->NewStringUTF(url);
    jstring jDest = env->NewStringUTF(destPath);
    if (!jUrl || !jDest) {
        if (jUrl) env->DeleteLocalRef(jUrl);
        if (jDest) env->DeleteLocalRef(jDest);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) javaVM->DetachCurrentThread();
        return false;
    }

    env->CallVoidMethod(this->activity, this->s_downloadCustomModel,
                        static_cast<jint>(fileType),
                        static_cast<jint>(crc),
                        jUrl,
                        jDest);

    bool ok = true;
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        ok = false;
    }

    env->DeleteLocalRef(jUrl);
    env->DeleteLocalRef(jDest);
    if (attached) {
        javaVM->DetachCurrentThread();
    }
    return ok;
}