#include <jni.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>

extern char *collector_call(char *, char *);
extern void collector_free(char *);
static JavaVM *vm;
static jclass bridge;
static jmethodID event;
static jmethodID interfaces;
static pthread_mutex_t init_mutex = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *value, void *reserved) {
    (void)reserved; vm=value; return JNI_VERSION_1_6;
}

char *collector_interfaces(void) {
    JNIEnv *env=NULL;
    int attached=0;
    jint status=(*vm)->GetEnv(vm,(void **)&env,JNI_VERSION_1_6);
    if(status==JNI_EDETACHED) {
        if((*vm)->AttachCurrentThread(vm,&env,NULL)!=JNI_OK) return NULL;
        attached=1;
    } else if(status!=JNI_OK) return NULL;
    char *output=NULL;
    jstring json=(jstring)(*env)->CallStaticObjectMethod(env,bridge,interfaces);
    if(json!=NULL && !(*env)->ExceptionCheck(env)) {
        const char *value=(*env)->GetStringUTFChars(env,json,NULL);
        if(value!=NULL) { output=strdup(value);(*env)->ReleaseStringUTFChars(env,json,value); }
        (*env)->DeleteLocalRef(env,json);
    }
    if((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env);(*env)->ExceptionClear(env); }
    if(attached) (*vm)->DetachCurrentThread(vm);
    return output;
}

void collector_event(const char *value) {
    JNIEnv *env=NULL;
    int attached=0;
    jint status=(*vm)->GetEnv(vm,(void **)&env,JNI_VERSION_1_6);
    if(status==JNI_EDETACHED) {
        if((*vm)->AttachCurrentThread(vm,&env,NULL)!=JNI_OK) return;
        attached=1;
    } else if(status!=JNI_OK) return;
    jstring json=(*env)->NewStringUTF(env,value);
    if(json!=NULL) {
        (*env)->CallStaticVoidMethod(env,bridge,event,json);
        (*env)->DeleteLocalRef(env,json);
    }
    if((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if(attached) (*vm)->DetachCurrentThread(vm);
}

JNIEXPORT jstring JNICALL Java_com_bydcollector_collector_ha_tailscale_NativeTailscale_call(
        JNIEnv *env,jclass clazz,jstring operation,jstring request) {
    if(operation==NULL || request==NULL) return NULL;
    pthread_mutex_lock(&init_mutex);
    if(bridge==NULL) {
        jmethodID method=(*env)->GetStaticMethodID(env,clazz,"onEvent","(Ljava/lang/String;)V");
        jmethodID getter=method==NULL ? NULL : (*env)->GetStaticMethodID(env,clazz,"interfaces","()Ljava/lang/String;");
        if(method!=NULL && getter!=NULL) { bridge=(*env)->NewGlobalRef(env,clazz);event=method;interfaces=getter; }
    }
    pthread_mutex_unlock(&init_mutex);
    if(bridge==NULL || (*env)->ExceptionCheck(env)) return NULL;
    const char *op=(*env)->GetStringUTFChars(env,operation,NULL);
    if(op==NULL) return NULL;
    const char *body=(*env)->GetStringUTFChars(env,request,NULL);
    if(body==NULL) { (*env)->ReleaseStringUTFChars(env,operation,op);return NULL; }
    char *response=collector_call((char *)op,(char *)body);
    (*env)->ReleaseStringUTFChars(env,operation,op);
    (*env)->ReleaseStringUTFChars(env,request,body);
    if(response==NULL) return NULL;
    jstring output=(*env)->NewStringUTF(env,response);
    collector_free(response);
    return output;
}
