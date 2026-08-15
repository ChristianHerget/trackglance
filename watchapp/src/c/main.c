#include <pebble.h>

#define PROTOCOL_VERSION 3
#define RELEASE_VERSION "0.1.3"
#define MAX_SLOTS 6
#define MAX_PROFILES 8
#define NAME_SIZE 21
#define LOCUS_NAME_SIZE 256
#define CONFIG_SIZE 4096
#define PROFILE_LIST_SIZE 8192
#define UNAVAILABLE INT32_MIN
#define PERSIST_CONFIG 100
#define PERSIST_PENDING_CONFIG 101
#define PERSIST_PROFILE_LIST 102

enum { MSG_SNAPSHOT=1, MSG_COMMAND=2, MSG_COMMAND_RESULT=3, MSG_REQUEST_SNAPSHOT=4,
  MSG_CONFIG_CHUNK=5, MSG_PROFILE_LIST_CHUNK=6, MSG_REQUEST_PROFILE_LIST=7 };
enum { STATE_STOPPED=0, STATE_RECORDING=1, STATE_PAUSED=2, STATE_UNAVAILABLE=3 };
enum { CMD_START=1, CMD_PAUSE_RESUME=2, CMD_STOP_SAVE=3, CMD_ADD_WAYPOINT=4 };
enum { RESULT_OK=0, RESULT_FAILED=3, RESULT_INVALID_PROFILE=4, RESULT_PROFILE_NOT_FOUND=5 };
enum { METRIC_ELAPSED=1, METRIC_MOVING_TIME=2, METRIC_DISTANCE=3, METRIC_MOVING_DISTANCE=4,
  METRIC_CURRENT_SPEED=5, METRIC_AVERAGE_SPEED=6, METRIC_MAX_SPEED=7, METRIC_CURRENT_PACE=8,
  METRIC_AVERAGE_PACE=9, METRIC_ALTITUDE=10, METRIC_ASCENT=11, METRIC_DESCENT=12,
  METRIC_VERTICAL_SPEED=13, METRIC_SLOPE=14, METRIC_AVG_HR=15, METRIC_MAX_HR=16,
  METRIC_AVG_CADENCE=17, METRIC_MAX_CADENCE=18, METRIC_AVG_POWER=19,
  METRIC_MAX_POWER=20, METRIC_ENERGY=21 };

typedef struct {
  int state;
  uint32_t sample_epoch, elapsed, moving_time;
  int32_t distance, moving_distance, current_speed, average_speed, max_speed;
  int32_t altitude, ascent, descent, vertical_speed, slope;
  int32_t avg_hr, max_hr, avg_cadence, max_cadence, avg_power, max_power, energy;
} Snapshot;
typedef struct { char name[NAME_SIZE], locus[LOCUS_NAME_SIZE]; bool protected_profile; uint8_t count, metrics[MAX_SLOTS]; } Profile;

static Window *s_main_window, *s_controls_window, *s_confirm_window;
static StatusBarLayer *s_status_bar;
static TextLayer *s_header, *s_labels[MAX_SLOTS], *s_values_layers[MAX_SLOTS];
static SimpleMenuLayer *s_menu, *s_confirm_menu;
static SimpleMenuItem s_items[5], s_confirm_items[2];
static SimpleMenuSection s_section, s_confirm_section;
static Snapshot s = {.state=STATE_UNAVAILABLE, .moving_time=UNAVAILABLE, .distance=UNAVAILABLE,
  .moving_distance=UNAVAILABLE, .current_speed=UNAVAILABLE, .average_speed=UNAVAILABLE,
  .max_speed=UNAVAILABLE, .altitude=UNAVAILABLE, .ascent=UNAVAILABLE, .descent=UNAVAILABLE,
  .vertical_speed=UNAVAILABLE, .slope=UNAVAILABLE, .avg_hr=UNAVAILABLE, .max_hr=UNAVAILABLE,
  .avg_cadence=UNAVAILABLE, .max_cadence=UNAVAILABLE, .avg_power=UNAVAILABLE,
  .max_power=UNAVAILABLE, .energy=UNAVAILABLE};
static Profile s_profiles[MAX_PROFILES];
static int s_profile_count, s_selected, s_pending_command;
static bool s_dark=true;
static uint32_t s_next_command_id, s_session_id;
static char s_header_text[52]="Connecting...", s_notice[40], s_value_text[MAX_SLOTS][24];
static time_t s_notice_until;
static char s_chunks[CONFIG_SIZE];
static char s_pending_chunks[CONFIG_SIZE];
static char s_config_work[CONFIG_SIZE];
static int s_transfer=-1, s_chunk_count, s_next_chunk;
static char s_profile_chunks[PROFILE_LIST_SIZE];
static int s_profile_transfer=-1, s_profile_chunk_count, s_profile_next_chunk;
static int s_profile_result=RESULT_FAILED;
static char s_profile_parts[128][81];
static bool s_profile_received[128];
static bool s_german;
static bool s_request_profiles_after_send;
static size_t s_relay_offset;
static int s_relay_index, s_relay_count, s_relay_id;

static int32_t get_int(DictionaryIterator *i, uint32_t key, int32_t fallback) {
  Tuple *t=dict_find(i,key); return t ? t->value->int32 : fallback;
}
static void copy_name(char *dst, const char *src) { snprintf(dst,NAME_SIZE,"%s",src?src:""); }
static void copy_locus_name(char *dst, const char *src) { snprintf(dst,LOCUS_NAME_SIZE,"%s",src?src:""); }
static const char *tr(const char *en, const char *de) { return s_german ? de : en; }
static char *next_token(char **cursor, char delimiter) {
  if (!cursor || !*cursor) return NULL;
  char *value = *cursor;
  char *end = strchr(value, delimiter);
  if (end) { *end = 0; *cursor = end + 1; } else { *cursor = NULL; }
  return value;
}

static void defaults(void) {
  s_dark=true; s_selected=0; s_profile_count=3;
  copy_name(s_profiles[0].name,tr("Hiking","Wandern")); copy_locus_name(s_profiles[0].locus,tr("Hiking","Wandern"));
  uint8_t h[]={1,3,5,6,10,11}; memcpy(s_profiles[0].metrics,h,6); s_profiles[0].count=6;
  copy_name(s_profiles[1].name,tr("Cycling","Radfahren")); copy_locus_name(s_profiles[1].locus,tr("Cycling","Radfahren"));
  uint8_t c[]={1,3,5,6,7,11}; memcpy(s_profiles[1].metrics,c,6); s_profiles[1].count=6;
  copy_name(s_profiles[2].name,tr("Running","Laufen")); copy_locus_name(s_profiles[2].locus,tr("Running","Laufen"));
  uint8_t r[]={1,3,8,9,15,17}; memcpy(s_profiles[2].metrics,r,6); s_profiles[2].count=6;
  for(int i=0;i<3;i++)s_profiles[i].protected_profile=false;
}

static bool parse_config(char *data) {
  Profile parsed[MAX_PROFILES]; memset(parsed,0,sizeof(parsed));
  int count=0, selected=0; bool dark=true;
  char *line_cursor=data, *line=next_token(&line_cursor,'\n'); if(!line)return false;
  char *header_cursor=line, *theme=next_token(&header_cursor,'|');
  char *selected_text=next_token(&header_cursor,'|');
  if(!theme||!selected_text)return false;
  selected=atoi(selected_text); dark=strcmp(theme,"light")!=0;
  while((line=next_token(&line_cursor,'\n')) && count<MAX_PROFILES) {
    char *field_cursor=line, *name=next_token(&field_cursor,'|'), *locus=next_token(&field_cursor,'|');
    char *prot=next_token(&field_cursor,'|'), *metrics=next_token(&field_cursor,'|');
    if(!name||!locus||!prot||!metrics||strlen(name)>20||strlen(locus)>=LOCUS_NAME_SIZE||!*name||!*locus)return false;
    copy_name(parsed[count].name,name); copy_locus_name(parsed[count].locus,locus);
    parsed[count].protected_profile=false; /* field retained on wire for protocol-v3 compatibility */
    char *metric_cursor=metrics, *m=next_token(&metric_cursor,',');
    while(m&&parsed[count].count<MAX_SLOTS){int id=atoi(m);if(id<1||id>21)return false;for(int j=0;j<parsed[count].count;j++)if(parsed[count].metrics[j]==id)return false;parsed[count].metrics[parsed[count].count++]=id;m=next_token(&metric_cursor,',');}
    if(!parsed[count].count||m)return false;
    count++;
  }
  if(count<1||selected<0||selected>=count)return false;
  memcpy(s_profiles,parsed,sizeof(parsed));s_profile_count=count;s_selected=selected;s_dark=dark;return true;
}

static void apply_theme(void) {
  GColor bg=s_dark?GColorBlack:GColorWhite, fg=s_dark?GColorWhite:GColorBlack;
  window_set_background_color(s_main_window,bg); window_set_background_color(s_controls_window,bg);
  window_set_background_color(s_confirm_window,bg);
  if(s_status_bar){status_bar_layer_set_colors(s_status_bar,bg,fg);}
  if(s_header){text_layer_set_text_color(s_header,fg);}
  for(int i=0;i<MAX_SLOTS;i++){if(s_labels[i])text_layer_set_text_color(s_labels[i],fg);if(s_values_layers[i])text_layer_set_text_color(s_values_layers[i],fg);}
  if(s_menu){MenuLayer *m=simple_menu_layer_get_menu_layer(s_menu);menu_layer_set_normal_colors(m,bg,fg);menu_layer_set_highlight_colors(m,fg,bg);}
  if(s_confirm_menu){MenuLayer *m=simple_menu_layer_get_menu_layer(s_confirm_menu);menu_layer_set_normal_colors(m,bg,fg);menu_layer_set_highlight_colors(m,fg,bg);}
}

static const char *metric_label(int m){switch(m){case 1:return tr("Elapsed","Gesamtzeit");case 2:return tr("Moving","Bewegungszeit");case 3:return tr("Distance","Strecke");case 4:return tr("Move dist","Bewegungsstr.");case 5:return tr("Speed","Tempo");case 6:return tr("Average","Durchschnitt");case 7:return tr("Max speed","Max. Tempo");case 8:return tr("Pace","Pace");case 9:return tr("Avg pace","Ø Pace");case 10:return tr("Altitude","Höhe");case 11:return tr("Ascent","Anstieg");case 12:return tr("Descent","Abstieg");case 13:return tr("Vertical","Vertikal");case 14:return tr("Slope","Steigung");case 15:return tr("Avg HR","Ø Puls");case 16:return tr("Max HR","Max. Puls");case 17:return tr("Avg cadence","Ø Frequenz");case 18:return tr("Max cadence","Max. Frequenz");case 19:return tr("Avg power","Ø Leistung");case 20:return tr("Max power","Max. Leistung");case 21:return tr("Energy","Energie");default:return "";}}
static int32_t metric_value(int m){switch(m){case 2:return s.moving_time;case 3:return s.distance;case 4:return s.moving_distance;case 5:case 8:return s.current_speed;case 6:case 9:return s.average_speed;case 7:return s.max_speed;case 10:return s.altitude;case 11:return s.ascent;case 12:return s.descent;case 13:return s.vertical_speed;case 14:return s.slope;case 15:return s.avg_hr;case 16:return s.max_hr;case 17:return s.avg_cadence;case 18:return s.max_cadence;case 19:return s.avg_power;case 20:return s.max_power;case 21:return s.energy;default:return 0;}}
static void format_metric(char *out,size_t n,int m,uint32_t elapsed){
  int32_t v=metric_value(m); if(m==1)v=(int32_t)elapsed;
  if(v==UNAVAILABLE){snprintf(out,n,"—");return;}
  if(m==1||m==2){snprintf(out,n,"%02ld:%02ld:%02ld",(long)v/3600,(long)(v/60)%60,(long)v%60);}
  else if(m==3||m==4){snprintf(out,n,"%ld.%02ld km",(long)v/1000,labs((long)v%1000)/10);}
  else if(m>=5&&m<=7){int32_t k=v*36/100;snprintf(out,n,"%ld.%ld km/h",(long)k/10,labs((long)k%10));}
  else if(m==8||m==9){if(v<=0)snprintf(out,n,"—");else{int sec=100000/v;snprintf(out,n,"%d:%02d /km",sec/60,sec%60);}}
  else if(m>=10&&m<=12)snprintf(out,n,"%ld m",(long)v/10);
  else if(m==13)snprintf(out,n,"%ld.%02ld m/s",(long)v/100,labs((long)v%100));
  else if(m==14)snprintf(out,n,"%ld.%ld%%",(long)v/10,labs((long)v%10));
  else if(m==15||m==16)snprintf(out,n,"%ld bpm",(long)v);
  else if(m==17||m==18)snprintf(out,n,"%ld rpm",(long)v);
  else if(m==19||m==20)snprintf(out,n,"%ld W",(long)v);
  else snprintf(out,n,"%ld kcal",(long)v);
}

static void layout_slots(void) {
  if(!s_labels[0])return;
  Layer *root=window_get_root_layer(s_main_window);GRect b=layer_get_bounds(root);
  int count=s_profiles[s_selected].count, top=STATUS_BAR_LAYER_HEIGHT+25, inset=0;
#ifdef PBL_ROUND
  inset=24; top+=6;
#endif
  int width=b.size.w-2*inset,height=b.size.h-top-(PBL_IF_ROUND_ELSE(18,0));
  for(int i=0;i<MAX_SLOTS;i++){
    bool visible=i<count;layer_set_hidden(text_layer_get_layer(s_labels[i]),!visible);layer_set_hidden(text_layer_get_layer(s_values_layers[i]),!visible);if(!visible)continue;
    int row,col,cw,rh,x,y;
    if(count<=3){row=i;col=0;cw=width;rh=height/count;x=inset;y=top+row*rh;}
    else if(count==5&&i==0){row=0;col=0;cw=width;rh=height/3;x=inset;y=top;}
    else {int slot=count==5?i-1:i;row=(count==5?1:0)+slot/2;col=slot%2;cw=width/2;rh=height/(count==4?2:3);x=inset+col*cw;y=top+row*rh;}
    layer_set_frame(text_layer_get_layer(s_labels[i]),GRect(x,y,cw,rh/2));
    layer_set_frame(text_layer_get_layer(s_values_layers[i]),GRect(x,y+rh/3,cw,rh*2/3));
    text_layer_set_text(s_labels[i],metric_label(s_profiles[s_selected].metrics[i]));
  }
}

static void render(void){time_t now=time(NULL);bool stale=!s.sample_epoch||now-(time_t)s.sample_epoch>30;const char *state=s.state==STATE_RECORDING?tr("Recording","Aufzeichnung"):s.state==STATE_PAUSED?tr("Paused","Pausiert"):s.state==STATE_STOPPED?tr("Stopped","Gestoppt"):tr("No Locus","Kein Locus");snprintf(s_header_text,sizeof(s_header_text),"%s%s",now<s_notice_until?s_notice:state,(now>=s_notice_until&&stale)?tr(" | stale"," | veraltet"):"");text_layer_set_text(s_header,s_header_text);uint32_t elapsed=s.elapsed;if(!stale&&s.state==STATE_RECORDING&&now>(time_t)s.sample_epoch)elapsed+=now-s.sample_epoch;for(int i=0;i<s_profiles[s_selected].count;i++){format_metric(s_value_text[i],sizeof(s_value_text[i]),s_profiles[s_selected].metrics[i],elapsed);text_layer_set_text(s_values_layers[i],s_value_text[i]);}}

static void send_message(int type,int command){DictionaryIterator *it;if(app_message_outbox_begin(&it)!=APP_MSG_OK)return;dict_write_int32(it,MESSAGE_KEY_PROTOCOL_VERSION,PROTOCOL_VERSION);dict_write_int32(it,MESSAGE_KEY_MESSAGE_TYPE,type);dict_write_cstring(it,MESSAGE_KEY_APP_VERSION,RELEASE_VERSION);if(type==MSG_COMMAND){dict_write_uint32(it,MESSAGE_KEY_COMMAND_ID,s_next_command_id++);dict_write_uint32(it,MESSAGE_KEY_SESSION_ID,s_session_id);dict_write_int32(it,MESSAGE_KEY_COMMAND,command);if(command==CMD_START)dict_write_cstring(it,MESSAGE_KEY_LOCUS_PROFILE_NAME,s_profiles[s_selected].locus);}app_message_outbox_send();}
static void relay_profile_next(void){size_t length=strlen(s_profile_chunks);if(s_relay_index>=s_relay_count)return;size_t end=s_relay_offset+80;if(end>length)end=length;while(end>s_relay_offset&&end<length&&(((uint8_t)s_profile_chunks[end]&0xc0)==0x80))end--;char part[81];size_t n=end-s_relay_offset;memcpy(part,s_profile_chunks+s_relay_offset,n);part[n]=0;DictionaryIterator *it;if(app_message_outbox_begin(&it)!=APP_MSG_OK)return;dict_write_int32(it,MESSAGE_KEY_PROTOCOL_VERSION,PROTOCOL_VERSION);dict_write_int32(it,MESSAGE_KEY_MESSAGE_TYPE,MSG_PROFILE_LIST_CHUNK);dict_write_int32(it,MESSAGE_KEY_RESULT,s_profile_result);dict_write_cstring(it,MESSAGE_KEY_APP_VERSION,RELEASE_VERSION);dict_write_int32(it,MESSAGE_KEY_TRANSFER_ID,s_relay_id);dict_write_int32(it,MESSAGE_KEY_CHUNK_INDEX,s_relay_index);dict_write_int32(it,MESSAGE_KEY_CHUNK_COUNT,s_relay_count);dict_write_cstring(it,MESSAGE_KEY_CHUNK_DATA,part);s_relay_offset=end;s_relay_index++;app_message_outbox_send();}
static void relay_profile_list(void){size_t length=strlen(s_profile_chunks),offset=0;if(length)s_profile_result=RESULT_OK;s_relay_count=0;do{size_t end=offset+80;if(end>length)end=length;while(end>offset&&end<length&&(((uint8_t)s_profile_chunks[end]&0xc0)==0x80))end--;offset=end;s_relay_count++;}while(offset<length);s_relay_offset=0;s_relay_index=0;s_relay_id=(int)(time(NULL)&0x7fffffff);relay_profile_next();}
static void outbox_sent(DictionaryIterator *it,void *context){if(s_relay_index<s_relay_count){relay_profile_next();return;}if(s_request_profiles_after_send){s_request_profiles_after_send=false;send_message(MSG_REQUEST_PROFILE_LIST,0);}}
static void accept_profile_chunk(DictionaryIterator *it){int id=get_int(it,MESSAGE_KEY_TRANSFER_ID,-1),idx=get_int(it,MESSAGE_KEY_CHUNK_INDEX,-1),count=get_int(it,MESSAGE_KEY_CHUNK_COUNT,0);Tuple *t=dict_find(it,MESSAGE_KEY_CHUNK_DATA);if(!t||count<1||count>128||idx<0||idx>=count||strlen(t->value->cstring)>80)return;if(id!=s_profile_transfer||count!=s_profile_chunk_count){s_profile_transfer=id;s_profile_chunk_count=count;s_profile_next_chunk=0;s_profile_result=get_int(it,MESSAGE_KEY_RESULT,RESULT_FAILED);memset(s_profile_received,0,sizeof(s_profile_received));}if(s_profile_received[idx])return;snprintf(s_profile_parts[idx],sizeof(s_profile_parts[idx]),"%s",t->value->cstring);s_profile_received[idx]=true;s_profile_next_chunk++;if(s_profile_next_chunk==s_profile_chunk_count){s_profile_chunks[0]=0;for(int i=0;i<count;i++){if(!s_profile_received[i]||strlen(s_profile_chunks)+strlen(s_profile_parts[i])>=PROFILE_LIST_SIZE){s_profile_transfer=-1;return;}strcat(s_profile_chunks,s_profile_parts[i]);}if(s_profile_chunks[0])persist_write_string(PERSIST_PROFILE_LIST,s_profile_chunks);s_profile_transfer=-1;relay_profile_list();}}
static void send_command(int command){s_pending_command=command;snprintf(s_notice,sizeof(s_notice),"%s",tr("Sending...","Senden..."));s_notice_until=time(NULL)+5;render();send_message(MSG_COMMAND,command);window_stack_pop(true);}
static void confirm_selected(int index,void *context){if(index==0){window_stack_pop(false);send_command(CMD_STOP_SAVE);}else window_stack_pop(true);}
static void profile_selected(int index,void *context){if(s.state!=STATE_STOPPED){snprintf(s_notice,sizeof(s_notice),"%s",tr("Profile locked","Profil gesperrt"));s_notice_until=time(NULL)+3;}else{s_selected=(s_selected+1)%s_profile_count;layout_slots();render();}window_stack_pop(true);}
static void control_selected(int index,void *context){int cmd;if(s.state==STATE_STOPPED)cmd=CMD_START;else if(index==1){profile_selected(index,context);return;}else if(index==0)cmd=CMD_PAUSE_RESUME;else if(index==2)cmd=CMD_STOP_SAVE;else cmd=CMD_ADD_WAYPOINT;if(cmd==CMD_STOP_SAVE)window_stack_push(s_confirm_window,true);else send_command(cmd);}

static void rebuild_menu(void){int n=0;s_items[n++]=(SimpleMenuItem){.title=s.state==STATE_STOPPED?tr("Start recording","Aufzeichnung starten"):s.state==STATE_PAUSED?tr("Resume","Fortsetzen"):s.state==STATE_RECORDING?tr("Pause","Pausieren"):tr("Locus unavailable","Locus nicht verfügbar"),.callback=s.state==STATE_UNAVAILABLE?NULL:control_selected};s_items[n++]=(SimpleMenuItem){.title=tr("Profile","Profil"),.subtitle=s_profiles[s_selected].name,.callback=profile_selected};if(s.state==STATE_RECORDING||s.state==STATE_PAUSED)s_items[n++]=(SimpleMenuItem){.title=tr("Stop & save","Stoppen & speichern"),.callback=control_selected};if(s.state==STATE_RECORDING)s_items[n++]=(SimpleMenuItem){.title=tr("Add waypoint","Wegpunkt hinzufügen"),.callback=control_selected};s_section=(SimpleMenuSection){.title=tr("Controls","Steuerung"),.num_items=n,.items=s_items};if(s_menu)simple_menu_layer_destroy(s_menu);s_menu=simple_menu_layer_create(layer_get_bounds(window_get_root_layer(s_controls_window)),s_controls_window,&s_section,1,NULL);layer_add_child(window_get_root_layer(s_controls_window),simple_menu_layer_get_layer(s_menu));apply_theme();}
static void main_select(ClickRecognizerRef r,void *c){rebuild_menu();window_stack_push(s_controls_window,true);}static void click_config(void *c){window_single_click_subscribe(BUTTON_ID_SELECT,main_select);}

static TextLayer *make_text(Layer *root,GRect frame,GFont font){TextLayer *l=text_layer_create(frame);text_layer_set_background_color(l,GColorClear);text_layer_set_font(l,font);text_layer_set_text_alignment(l,GTextAlignmentCenter);layer_add_child(root,text_layer_get_layer(l));return l;}
static void main_load(Window *w){Layer *root=window_get_root_layer(w);GRect b=layer_get_bounds(root);s_status_bar=status_bar_layer_create();layer_add_child(root,status_bar_layer_get_layer(s_status_bar));s_header=make_text(root,GRect(0,STATUS_BAR_LAYER_HEIGHT,b.size.w,25),fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));for(int i=0;i<MAX_SLOTS;i++){s_labels[i]=make_text(root,GRectZero,fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));s_values_layers[i]=make_text(root,GRectZero,fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));}layout_slots();apply_theme();render();}
static void main_unload(Window *w){status_bar_layer_destroy(s_status_bar);text_layer_destroy(s_header);for(int i=0;i<MAX_SLOTS;i++){text_layer_destroy(s_labels[i]);text_layer_destroy(s_values_layers[i]);}}
static void controls_unload(Window *w){if(s_menu){simple_menu_layer_destroy(s_menu);s_menu=NULL;}}
static void confirm_load(Window *w){s_confirm_items[0]=(SimpleMenuItem){.title=tr("Save & stop","Speichern & stoppen"),.subtitle=tr("Finish the recording","Aufzeichnung beenden"),.callback=confirm_selected};s_confirm_items[1]=(SimpleMenuItem){.title=tr("Cancel","Abbrechen"),.subtitle=tr("Keep recording","Weiter aufzeichnen"),.callback=confirm_selected};s_confirm_section=(SimpleMenuSection){.title=tr("Stop recording?","Aufzeichnung stoppen?"),.num_items=2,.items=s_confirm_items};s_confirm_menu=simple_menu_layer_create(layer_get_bounds(window_get_root_layer(w)),w,&s_confirm_section,1,NULL);layer_add_child(window_get_root_layer(w),simple_menu_layer_get_layer(s_confirm_menu));apply_theme();}
static void confirm_unload(Window *w){simple_menu_layer_destroy(s_confirm_menu);s_confirm_menu=NULL;}

static void accept_config_chunk(DictionaryIterator *it){int id=get_int(it,MESSAGE_KEY_TRANSFER_ID,-1),idx=get_int(it,MESSAGE_KEY_CHUNK_INDEX,-1),count=get_int(it,MESSAGE_KEY_CHUNK_COUNT,0);Tuple *t=dict_find(it,MESSAGE_KEY_CHUNK_DATA);if(!t||count<1||idx<0||idx>=count)return;if(id!=s_transfer||idx==0){s_transfer=id;s_chunk_count=count;s_next_chunk=0;s_chunks[0]=0;}if(idx!=s_next_chunk||strlen(s_chunks)+t->length>=CONFIG_SIZE)return;strcat(s_chunks,t->value->cstring);s_next_chunk++;if(s_next_chunk==s_chunk_count){if(s.state==STATE_RECORDING||s.state==STATE_PAUSED){snprintf(s_pending_chunks,sizeof(s_pending_chunks),"%s",s_chunks);persist_write_string(PERSIST_PENDING_CONFIG,s_pending_chunks);}else{snprintf(s_config_work,sizeof(s_config_work),"%s",s_chunks);if(parse_config(s_config_work)){persist_write_string(PERSIST_CONFIG,s_chunks);layout_slots();apply_theme();render();}}s_transfer=-1;}}
static void inbox(DictionaryIterator *it,void *context){if(get_int(it,MESSAGE_KEY_PROTOCOL_VERSION,0)!=PROTOCOL_VERSION)return;Tuple *release=dict_find(it,MESSAGE_KEY_APP_VERSION);if(!release||strcmp(release->value->cstring,RELEASE_VERSION)){snprintf(s_notice,sizeof(s_notice),"%s",tr("Update bridge/watch","Bridge/Watch aktualisieren"));s_notice_until=time(NULL)+10;render();return;}int type=get_int(it,MESSAGE_KEY_MESSAGE_TYPE,0);if(type==MSG_CONFIG_CHUNK){accept_config_chunk(it);return;}if(type==MSG_PROFILE_LIST_CHUNK){accept_profile_chunk(it);return;}if(type==MSG_REQUEST_PROFILE_LIST){if(persist_exists(PERSIST_PROFILE_LIST)){persist_read_string(PERSIST_PROFILE_LIST,s_profile_chunks,sizeof(s_profile_chunks));s_request_profiles_after_send=true;relay_profile_list();}else send_message(MSG_REQUEST_PROFILE_LIST,0);return;}if(type==MSG_SNAPSHOT){int old=s.state;s.state=get_int(it,MESSAGE_KEY_RECORDING_STATE,STATE_UNAVAILABLE);s.sample_epoch=get_int(it,MESSAGE_KEY_SAMPLE_EPOCH_SECONDS,0);s.elapsed=get_int(it,MESSAGE_KEY_ELAPSED_SECONDS,0);s.moving_time=get_int(it,MESSAGE_KEY_MOVING_SECONDS,UNAVAILABLE);s.distance=get_int(it,MESSAGE_KEY_DISTANCE_METRES,UNAVAILABLE);s.moving_distance=get_int(it,MESSAGE_KEY_MOVING_DISTANCE_METRES,UNAVAILABLE);s.current_speed=get_int(it,MESSAGE_KEY_CURRENT_SPEED_CMPS,UNAVAILABLE);s.average_speed=get_int(it,MESSAGE_KEY_AVERAGE_SPEED_CMPS,UNAVAILABLE);s.max_speed=get_int(it,MESSAGE_KEY_MAX_SPEED_CMPS,UNAVAILABLE);s.altitude=get_int(it,MESSAGE_KEY_ALTITUDE_DECIMETRES,UNAVAILABLE);s.ascent=get_int(it,MESSAGE_KEY_ASCENT_DECIMETRES,UNAVAILABLE);s.descent=get_int(it,MESSAGE_KEY_DESCENT_DECIMETRES,UNAVAILABLE);s.vertical_speed=get_int(it,MESSAGE_KEY_VERTICAL_SPEED_CMPS,UNAVAILABLE);s.slope=get_int(it,MESSAGE_KEY_SLOPE_TENTHS_PERCENT,UNAVAILABLE);s.avg_hr=get_int(it,MESSAGE_KEY_AVERAGE_HEART_RATE,UNAVAILABLE);s.max_hr=get_int(it,MESSAGE_KEY_MAX_HEART_RATE,UNAVAILABLE);s.avg_cadence=get_int(it,MESSAGE_KEY_AVERAGE_CADENCE,UNAVAILABLE);s.max_cadence=get_int(it,MESSAGE_KEY_MAX_CADENCE,UNAVAILABLE);s.avg_power=get_int(it,MESSAGE_KEY_AVERAGE_POWER,UNAVAILABLE);s.max_power=get_int(it,MESSAGE_KEY_MAX_POWER,UNAVAILABLE);s.energy=get_int(it,MESSAGE_KEY_ENERGY_KCAL,UNAVAILABLE);if(old!=STATE_STOPPED&&s.state==STATE_STOPPED&&persist_exists(PERSIST_PENDING_CONFIG)){persist_read_string(PERSIST_PENDING_CONFIG,s_pending_chunks,sizeof(s_pending_chunks));snprintf(s_config_work,sizeof(s_config_work),"%s",s_pending_chunks);if(parse_config(s_config_work)){persist_write_string(PERSIST_CONFIG,s_pending_chunks);layout_slots();apply_theme();}persist_delete(PERSIST_PENDING_CONFIG);}render();}else if(type==MSG_COMMAND_RESULT){int result=get_int(it,MESSAGE_KEY_RESULT,3);vibes_short_pulse();if(result==RESULT_INVALID_PROFILE)snprintf(s_notice,sizeof(s_notice),"Invalid profile");else if(result==RESULT_PROFILE_NOT_FOUND)snprintf(s_notice,sizeof(s_notice),"Profile not in Locus");else if(result==0&&s_pending_command==CMD_ADD_WAYPOINT)snprintf(s_notice,sizeof(s_notice),"Waypoint added");else if(result==0)snprintf(s_notice,sizeof(s_notice),"Command accepted");else snprintf(s_notice,sizeof(s_notice),"Command failed (%d)",result);s_notice_until=time(NULL)+4;render();}}
static void tick(struct tm *t,TimeUnits u){render();}

static void init(void){const char *locale=i18n_get_system_locale();s_german=locale&&strncmp(locale,"de",2)==0;defaults();if(persist_exists(PERSIST_CONFIG)){persist_read_string(PERSIST_CONFIG,s_config_work,sizeof(s_config_work));if(!parse_config(s_config_work))defaults();}s_session_id=time(NULL);s_next_command_id=1;s_main_window=window_create();window_set_window_handlers(s_main_window,(WindowHandlers){.load=main_load,.unload=main_unload});window_set_click_config_provider(s_main_window,click_config);s_controls_window=window_create();window_set_window_handlers(s_controls_window,(WindowHandlers){.unload=controls_unload});s_confirm_window=window_create();window_set_window_handlers(s_confirm_window,(WindowHandlers){.load=confirm_load,.unload=confirm_unload});app_message_register_inbox_received(inbox);app_message_register_outbox_sent(outbox_sent);app_message_open(1024,256);tick_timer_service_subscribe(SECOND_UNIT,tick);window_stack_push(s_main_window,true);s_request_profiles_after_send=true;send_message(MSG_REQUEST_SNAPSHOT,0);}
static void deinit(void){tick_timer_service_unsubscribe();window_destroy(s_confirm_window);window_destroy(s_controls_window);window_destroy(s_main_window);}int main(void){init();app_event_loop();deinit();}
