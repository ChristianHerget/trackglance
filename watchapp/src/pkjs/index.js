/* Offline configuration for the Pebble App. This file intentionally has no network dependencies. */
(function (root) {
  'use strict';

  var V = 5;
  var RELEASE = '0.2.8';
  var CONFIG = 'config';
  var CACHE = 'locusProfiles.v4';
  var NOTICE = 'configNotice.v4';
  var CONFIG_TRANSFER_SERIAL = 'configTransferSerial.v4';
  var PROFILE_TRANSFER_FLOOR = 'profileTransferFloor.v4';
  var DURABLE_TRANSFER_GENERATION = 1;
  var SERIAL_MASK = 0x7fffffff;
  var SERIAL_HALF_RANGE = 0x40000000;
  var LIMIT = {
    pages: 4, metrics: 6, displayNameCodePoints: 20, displayNameBytes: 80,
    locusNameBytes: 255, locusIdBytes: 20, idBytes: 39, configBytes: 4095,
    profileListBytes: 8191, chunkBytes: 80, profileChunks: 103,
    transferSerialMask: SERIAL_MASK, transferSerialHalfRange: SERIAL_HALF_RANGE,
    sendAttempts: 3, ackTimeoutMillis: 10000
  };
  var K = {
    v: 0, type: 1, result: 4, time: 6, session: 7, locusName: 9,
    index: 30, count: 31, data: 32, id: 33, release: 35, hr: 37,
    sequence: 38, generation: 39, locusId: 51, fingerprintA: 52, fingerprintB: 53,
    steps: 54, recordingLow: 55, recordingHigh: 56, stepSequence: 57
  };
  var M = {
    snapshot: 1, command: 2, commandResult: 3, requestSnapshot: 4,
    configChunk: 5, profileChunk: 6, requestProfiles: 7, heartRate: 8,
    configResult: 9, recordingContext: 10, requestRuntimeConfig: 11, stepDelta: 12
  };
  var R = {applied: 0, failed: 3, queued: 7, invalidConfig: 8, storageFailed: 9};
  var strings = {
    en: {
      title: 'Locus Bridge', activities: 'Activities', theme: 'Theme', dark: 'Dark', light: 'Light',
      heartRate: 'Heart Rate', sendHr: 'Send watch heart rate to Locus', hrInterval: 'Heart rate interval',
      seconds: 'seconds', edit: 'Edit', save: 'Save', done: 'Done', cancel: 'Cancel', reset: 'Reset',
      name: 'Display name', mapping: 'Locus activity', metrics: 'Metrics', addMetric: 'Add metric', clone: 'Clone page',
      remove: 'Remove', defaultName: 'Default', copySuffix: ' copy', drag: 'Drag to reorder',
      deleteLast: 'Deleting the last page immediately creates a new heuristic Default page. Continue?',
      confirmReset: 'Reset all activity pages and global settings to automatic defaults?',
      moveFull: 'The destination activity already has four pages.',
      invalid: 'Choose 1–6 unique metrics and valid page names.',
      duplicate: 'Display names must be unique within an activity.',
      discard: 'Discard all unsaved changes?', fresh: 'Locus activities updated.',
      stale: 'Using the last saved Locus activity list.',
      empty: 'Locus returned no recording activities; saved settings were not changed.',
      unavailable: 'No activity response received from the bridge yet.',
      incompatible: 'Incompatible bridge/watch version. Install version ' + RELEASE + ' on both devices.',
      storage: 'Settings could not be stored; the previous configuration was kept.'
    },
    de: {
      title: 'Locus Bridge', activities: 'Aktivitäten', theme: 'Darstellung', dark: 'Dunkel', light: 'Hell',
      heartRate: 'Herzfrequenz', sendHr: 'Uhrenpuls an Locus senden', hrInterval: 'Pulsintervall',
      seconds: 'Sekunden', edit: 'Bearbeiten', save: 'Speichern', done: 'Fertig', cancel: 'Abbrechen', reset: 'Zurücksetzen',
      name: 'Anzeigename', mapping: 'Locus-Aktivität', metrics: 'Messwerte', addMetric: 'Messwert hinzufügen', clone: 'Seite kopieren',
      remove: 'Entfernen', defaultName: 'Standard', copySuffix: ' Kopie', drag: 'Zum Sortieren ziehen',
      deleteLast: 'Beim Löschen der letzten Seite wird sofort eine heuristische Standardseite erstellt. Fortfahren?',
      confirmReset: 'Alle Aktivitätsseiten und allgemeinen Einstellungen automatisch zurücksetzen?',
      moveFull: 'Die Zielaktivität hat bereits vier Seiten.',
      invalid: '1–6 eindeutige Messwerte und gültige Seitennamen wählen.',
      duplicate: 'Anzeigenamen müssen innerhalb einer Aktivität eindeutig sein.',
      discard: 'Ungespeicherte Änderungen verwerfen?', fresh: 'Locus-Aktivitäten aktualisiert.',
      stale: 'Zuletzt gespeicherte Locus-Aktivitäten werden verwendet.',
      empty: 'Locus liefert keine Aufzeichnungsaktivitäten; gespeicherte Einstellungen wurden nicht geändert.',
      unavailable: 'Noch keine Aktivitätsantwort von der Bridge empfangen.',
      incompatible: 'Bridge und Watch sind inkompatibel. Version ' + RELEASE + ' auf beiden Geräten installieren.',
      storage: 'Einstellungen konnten nicht gespeichert werden; die vorige Konfiguration bleibt erhalten.'
    },
    fr: {
      title: 'Pont Locus', activities: 'Activités', theme: 'Thème', dark: 'Sombre', light: 'Clair',
      heartRate: 'Fréquence cardiaque', sendHr: 'Envoyer le pouls de la montre à Locus', hrInterval: 'Intervalle cardiaque',
      seconds: 'secondes', edit: 'Modifier', save: 'Enregistrer', done: 'Terminé', cancel: 'Annuler', reset: 'Réinitialiser',
      name: 'Nom affiché', mapping: 'Activité Locus', metrics: 'Mesures', addMetric: 'Ajouter une mesure', clone: 'Dupliquer la page',
      remove: 'Supprimer', defaultName: 'Par défaut', copySuffix: ' copie', drag: 'Faire glisser pour trier',
      deleteLast: 'Supprimer la dernière page crée immédiatement une nouvelle page par défaut. Continuer ?',
      confirmReset: 'Réinitialiser toutes les pages et les réglages ?', moveFull: 'L’activité cible possède déjà quatre pages.',
      invalid: 'Choisissez 1 à 6 mesures uniques et des noms valides.', duplicate: 'Les noms doivent être uniques dans une activité.',
      discard: 'Ignorer les modifications ?', fresh: 'Activités Locus actualisées.', stale: 'Dernière liste d’activités enregistrée utilisée.',
      empty: 'Locus n’a renvoyé aucune activité ; les réglages sont conservés.', unavailable: 'Aucune réponse du Bridge pour le moment.',
      incompatible: 'Versions Bridge/montre incompatibles. Installez la version ' + RELEASE + ' sur les deux appareils.',
      storage: 'Impossible d’enregistrer les réglages ; la configuration précédente est conservée.'
    },
    es: {
      title: 'Puente Locus', activities: 'Actividades', theme: 'Tema', dark: 'Oscuro', light: 'Claro',
      heartRate: 'Frecuencia cardíaca', sendHr: 'Enviar pulso del reloj a Locus', hrInterval: 'Intervalo cardíaco',
      seconds: 'segundos', edit: 'Editar', save: 'Guardar', done: 'Listo', cancel: 'Cancelar', reset: 'Restablecer',
      name: 'Nombre visible', mapping: 'Actividad Locus', metrics: 'Métricas', addMetric: 'Añadir métrica', clone: 'Duplicar página',
      remove: 'Eliminar', defaultName: 'Predeterminada', copySuffix: ' copia', drag: 'Arrastra para ordenar',
      deleteLast: 'Al borrar la última página se crea una nueva página predeterminada. ¿Continuar?',
      confirmReset: '¿Restablecer todas las páginas y ajustes?', moveFull: 'La actividad de destino ya tiene cuatro páginas.',
      invalid: 'Elige de 1 a 6 métricas únicas y nombres válidos.', duplicate: 'Los nombres deben ser únicos en cada actividad.',
      discard: '¿Descartar los cambios?', fresh: 'Actividades de Locus actualizadas.', stale: 'Se usa la última lista guardada.',
      empty: 'Locus no devolvió actividades; se conservan los ajustes.', unavailable: 'Aún no hay respuesta del Bridge.',
      incompatible: 'Versiones de Bridge/reloj incompatibles. Instala ' + RELEASE + ' en ambos dispositivos.',
      storage: 'No se pudieron guardar los ajustes; se conserva la configuración anterior.'
    },
    it: {
      title: 'Bridge Locus', activities: 'Attività', theme: 'Tema', dark: 'Scuro', light: 'Chiaro',
      heartRate: 'Frequenza cardiaca', sendHr: 'Invia battito dell’orologio a Locus', hrInterval: 'Intervallo cardiaco',
      seconds: 'secondi', edit: 'Modifica', save: 'Salva', done: 'Fatto', cancel: 'Annulla', reset: 'Ripristina',
      name: 'Nome visualizzato', mapping: 'Attività Locus', metrics: 'Metriche', addMetric: 'Aggiungi metrica', clone: 'Duplica pagina',
      remove: 'Rimuovi', defaultName: 'Predefinita', copySuffix: ' copia', drag: 'Trascina per riordinare',
      deleteLast: 'Eliminando l’ultima pagina ne verrà creata una predefinita. Continuare?',
      confirmReset: 'Ripristinare tutte le pagine e le impostazioni?', moveFull: 'L’attività di destinazione ha già quattro pagine.',
      invalid: 'Scegli 1–6 metriche uniche e nomi validi.', duplicate: 'I nomi devono essere unici in ogni attività.',
      discard: 'Ignorare le modifiche?', fresh: 'Attività Locus aggiornate.', stale: 'Viene usato l’ultimo elenco salvato.',
      empty: 'Locus non ha restituito attività; le impostazioni restano invariate.', unavailable: 'Nessuna risposta dal Bridge.',
      incompatible: 'Versioni Bridge/orologio incompatibili. Installa ' + RELEASE + ' su entrambi.',
      storage: 'Impossibile salvare; viene mantenuta la configurazione precedente.'
    },
    pt: {
      title: 'Ponte Locus', activities: 'Atividades', theme: 'Tema', dark: 'Escuro', light: 'Claro',
      heartRate: 'Frequência cardíaca', sendHr: 'Enviar pulso do relógio para o Locus', hrInterval: 'Intervalo cardíaco',
      seconds: 'segundos', edit: 'Editar', save: 'Guardar', done: 'Concluído', cancel: 'Cancelar', reset: 'Repor',
      name: 'Nome visível', mapping: 'Atividade Locus', metrics: 'Métricas', addMetric: 'Adicionar métrica', clone: 'Duplicar página',
      remove: 'Remover', defaultName: 'Predefinida', copySuffix: ' cópia', drag: 'Arraste para ordenar',
      deleteLast: 'Ao eliminar a última página será criada uma página predefinida. Continuar?',
      confirmReset: 'Repor todas as páginas e definições?', moveFull: 'A atividade de destino já tem quatro páginas.',
      invalid: 'Escolha 1–6 métricas únicas e nomes válidos.', duplicate: 'Os nomes devem ser únicos em cada atividade.',
      discard: 'Ignorar alterações?', fresh: 'Atividades Locus atualizadas.', stale: 'A usar a última lista guardada.',
      empty: 'O Locus não devolveu atividades; as definições foram mantidas.', unavailable: 'Ainda sem resposta do Bridge.',
      incompatible: 'Versões Bridge/relógio incompatíveis. Instale ' + RELEASE + ' em ambos.',
      storage: 'Não foi possível guardar; a configuração anterior foi mantida.'
    },
    zh_CN: {
      title: 'Locus 桥接', activities: '活动', theme: '主题', dark: '深色', light: '浅色',
      heartRate: '心率', sendHr: '将手表心率发送到 Locus', hrInterval: '心率间隔', seconds: '秒',
      edit: '编辑', save: '保存', done: '完成', cancel: '取消', reset: '重置', name: '显示名称', mapping: 'Locus 活动',
      metrics: '指标', addMetric: '添加指标', clone: '复制页面', remove: '删除', defaultName: '默认', copySuffix: ' 副本', drag: '拖动排序',
      deleteLast: '删除最后一页后会立即创建新的默认页。继续吗？', confirmReset: '重置所有活动页面和全局设置？',
      moveFull: '目标活动已有四页。', invalid: '请选择 1–6 个不重复的指标和有效页面名称。', duplicate: '同一活动中的显示名称不能重复。',
      discard: '放弃未保存的更改？', fresh: 'Locus 活动已更新。', stale: '正在使用上次保存的活动列表。',
      empty: 'Locus 未返回活动；已保留设置。', unavailable: '尚未收到 Bridge 的活动响应。',
      incompatible: 'Bridge 与手表版本不兼容。请在两台设备上安装 ' + RELEASE + '。', storage: '无法保存设置；已保留原配置。'
    },
    zh_TW: {
      title: 'Locus 橋接', activities: '活動', theme: '主題', dark: '深色', light: '淺色',
      heartRate: '心率', sendHr: '將手錶心率傳送到 Locus', hrInterval: '心率間隔', seconds: '秒',
      edit: '編輯', save: '儲存', done: '完成', cancel: '取消', reset: '重設', name: '顯示名稱', mapping: 'Locus 活動',
      metrics: '指標', addMetric: '新增指標', clone: '複製頁面', remove: '移除', defaultName: '預設', copySuffix: ' 副本', drag: '拖曳排序',
      deleteLast: '刪除最後一頁後會立即建立新的預設頁。繼續嗎？', confirmReset: '重設所有活動頁面和全域設定？',
      moveFull: '目標活動已有四頁。', invalid: '請選擇 1–6 個不重複的指標和有效頁面名稱。', duplicate: '同一活動中的顯示名稱不可重複。',
      discard: '放棄未儲存的變更？', fresh: 'Locus 活動已更新。', stale: '正在使用上次儲存的活動列表。',
      empty: 'Locus 未傳回活動；已保留設定。', unavailable: '尚未收到 Bridge 的活動回應。',
      incompatible: 'Bridge 與手錶版本不相容。請在兩台裝置上安裝 ' + RELEASE + '。', storage: '無法儲存設定；已保留原設定。'
    }
  };
  var uiEnglish={general:'General',generalSettings:'General settings',page:'Page',pages:'pages',inactivePage:'Inactive page',customName:'Custom page name',resetActivity:'Reset activity',resetGeneral:'Reset General',confirmActivityReset:'Reset this activity to its automatic page?',confirmGeneralReset:'Reset General settings?',finalPage:'At least one page must remain active.',moveUp:'Move up',moveDown:'Move down',moveTo:'Move to',position:'position',of:'of',openActivity:'Edit activity',grabbed:'Grabbed',dropped:'Dropped',dragCancelled:'Move cancelled',pageFull:'This page already has six metrics.',metricUsed:'This metric is already on that page.',sendSteps:'Use watch steps for this activity'};
  Object.keys(strings).forEach(function(language){Object.keys(uiEnglish).forEach(function(key){if(!strings[language][key])strings[language][key]=uiEnglish[key];});});
  var localizedPage={de:'Seite',fr:'Page',es:'Página',it:'Pagina',pt:'Página',zh_CN:'页面',zh_TW:'頁面'};
  Object.keys(localizedPage).forEach(function(language){strings[language].page=localizedPage[language];});
  var localizedGeneralSettings={de:'Allgemeine Einstellungen',fr:'Réglages généraux',es:'Ajustes generales',it:'Impostazioni generali',pt:'Definições gerais',zh_CN:'常规设置',zh_TW:'一般設定'};
  Object.keys(localizedGeneralSettings).forEach(function(language){strings[language].generalSettings=localizedGeneralSettings[language];});
  var localizedSendSteps={de:'Schritte der Uhr für diese Aktivität verwenden',fr:'Utiliser les pas de la montre pour cette activité',es:'Usar los pasos del reloj para esta actividad',it:'Usa i passi dell’orologio per questa attività',pt:'Usar os passos do relógio nesta atividade',zh_CN:'对此活动使用手表步数',zh_TW:'對此活動使用手錶步數'};
  Object.keys(localizedSendSteps).forEach(function(language){strings[language].sendSteps=localizedSendSteps[language];});
  var localizedDrag={
    de:{grabbed:'Aufgenommen',dropped:'Abgelegt',dragCancelled:'Verschieben abgebrochen',moveUp:'Nach oben',moveDown:'Nach unten',moveTo:'Verschieben nach',pageFull:'Diese Seite enthält bereits sechs Messwerte.',metricUsed:'Dieser Messwert ist bereits auf dieser Seite.'},
    fr:{grabbed:'Élément saisi',dropped:'Élément déposé',dragCancelled:'Déplacement annulé',moveUp:'Monter',moveDown:'Descendre',moveTo:'Déplacer vers',pageFull:'Cette page contient déjà six mesures.',metricUsed:'Cette mesure figure déjà sur cette page.'},
    es:{grabbed:'Elemento recogido',dropped:'Elemento soltado',dragCancelled:'Movimiento cancelado',moveUp:'Mover arriba',moveDown:'Mover abajo',moveTo:'Mover a',pageFull:'Esta página ya contiene seis métricas.',metricUsed:'Esta métrica ya está en esa página.'},
    it:{grabbed:'Elemento afferrato',dropped:'Elemento rilasciato',dragCancelled:'Spostamento annullato',moveUp:'Sposta su',moveDown:'Sposta giù',moveTo:'Sposta in',pageFull:'Questa pagina contiene già sei metriche.',metricUsed:'Questa metrica è già presente nella pagina.'},
    pt:{grabbed:'Item selecionado',dropped:'Item colocado',dragCancelled:'Movimento cancelado',moveUp:'Mover para cima',moveDown:'Mover para baixo',moveTo:'Mover para',pageFull:'Esta página já contém seis métricas.',metricUsed:'Esta métrica já está nesta página.'},
    zh_CN:{grabbed:'已选中',dropped:'已放置',dragCancelled:'已取消移动',moveUp:'上移',moveDown:'下移',moveTo:'移动到',pageFull:'此页面已有六个指标。',metricUsed:'此页面已有该指标。'},
    zh_TW:{grabbed:'已選取',dropped:'已放置',dragCancelled:'已取消移動',moveUp:'上移',moveDown:'下移',moveTo:'移動到',pageFull:'此頁面已有六個指標。',metricUsed:'此頁面已有該指標。'}
  };
  Object.keys(localizedDrag).forEach(function(language){Object.keys(localizedDrag[language]).forEach(function(key){strings[language][key]=localizedDrag[language][key];});});
  var metricNames = {
    en: ['Elapsed time','Moving time','Total distance','Moving distance','Current speed','Average speed','Max speed','Current pace','Average pace','Altitude','Ascent','Descent','Vertical speed','Slope','Average heart rate','Max heart rate','Average cadence','Max cadence','Average power','Max power','Energy','Current heart rate','Steps'],
    de: ['Gesamtzeit','Zeit in Bewegung','Gesamtstrecke','Strecke in Bewegung','Aktuelle Geschwindigkeit','Durchschnittsgeschwindigkeit','Höchstgeschwindigkeit','Aktuelles Tempo','Durchschnittstempo','Höhe','Anstieg','Abstieg','Vertikalgeschwindigkeit','Steigung','Durchschnittspuls','Maximalpuls','Durchschnittliche Trittfrequenz','Maximale Trittfrequenz','Durchschnittsleistung','Maximalleistung','Energie','Aktueller Puls'],
    fr: ['Temps écoulé','Temps en mouvement','Distance totale','Distance en mouvement','Vitesse actuelle','Vitesse moyenne','Vitesse maximale','Allure actuelle','Allure moyenne','Altitude','Montée','Descente','Vitesse verticale','Pente','FC moyenne','FC maximale','Cadence moyenne','Cadence maximale','Puissance moyenne','Puissance maximale','Énergie','FC actuelle'],
    es: ['Tiempo transcurrido','Tiempo en movimiento','Distancia total','Distancia en movimiento','Velocidad actual','Velocidad media','Velocidad máxima','Ritmo actual','Ritmo medio','Altitud','Ascenso','Descenso','Velocidad vertical','Pendiente','FC media','FC máxima','Cadencia media','Cadencia máxima','Potencia media','Potencia máxima','Energía','FC actual'],
    it: ['Tempo trascorso','Tempo in movimento','Distanza totale','Distanza in movimento','Velocità attuale','Velocità media','Velocità massima','Passo attuale','Passo medio','Altitudine','Salita','Discesa','Velocità verticale','Pendenza','FC media','FC massima','Cadenza media','Cadenza massima','Potenza media','Potenza massima','Energia','FC attuale'],
    pt: ['Tempo decorrido','Tempo em movimento','Distância total','Distância em movimento','Velocidade atual','Velocidade média','Velocidade máxima','Ritmo atual','Ritmo médio','Altitude','Subida','Descida','Velocidade vertical','Inclinação','FC média','FC máxima','Cadência média','Cadência máxima','Potência média','Potência máxima','Energia','FC atual'],
    zh_CN: ['已用时间','移动时间','总距离','移动距离','当前速度','平均速度','最高速度','当前配速','平均配速','海拔','上升','下降','垂直速度','坡度','平均心率','最高心率','平均步频','最高步频','平均功率','最大功率','能量','当前心率'],
    zh_TW: ['經過時間','移動時間','總距離','移動距離','目前速度','平均速度','最高速度','目前配速','平均配速','海拔','上升','下降','垂直速度','坡度','平均心率','最高心率','平均步頻','最高步頻','平均功率','最大功率','能量','目前心率']
  };
  var localizedSteps={de:'Schritte',fr:'Pas',es:'Pasos',it:'Passi',pt:'Passos',zh_CN:'步数',zh_TW:'步數'};
  Object.keys(localizedSteps).forEach(function(language){metricNames[language].push(localizedSteps[language]);});

  function locale(value) {
    var normalized = String(value || 'en').toLowerCase().replace('-', '_');
    if (/^(zh|en)_(cn|hans)/.test(normalized)) return 'zh_CN';
    if (/^(zh|en)_(tw|hant)/.test(normalized)) return 'zh_TW';
    var language = normalized.split('_')[0];
    return ['de','fr','es','it','pt'].indexOf(language) >= 0 ? language : 'en';
  }
  function catalogComplete() {
    var keys = Object.keys(strings.en).sort();
    return Object.keys(strings).every(function (language) {
      return keys.length === Object.keys(strings[language]).length && keys.every(function (key) {
        return strings[language][key];
      }) && metricNames[language].length === 23;
    });
  }
  function utf8Bytes(value) { try { return unescape(encodeURIComponent(String(value))).length; } catch (_) { return -1; } }
  function scanCodePoints(value, visitor) {
    for (var i = 0, count = 0; i < value.length; i++, count++) {
      var first = value.charCodeAt(i), point = first;
      if (first >= 0xd800 && first <= 0xdbff) {
        if (i + 1 >= value.length) return -1;
        var second = value.charCodeAt(++i);
        if (second < 0xdc00 || second > 0xdfff) return -1;
        point = 0x10000 + ((first - 0xd800) << 10) + second - 0xdc00;
      } else if (first >= 0xdc00 && first <= 0xdfff) return -1;
      if (visitor && !visitor(point, count)) return -1;
    }
    return count;
  }
  function unicodeSpace(point) { return point === 0x20 || point === 0x85 || point === 0xa0 || point === 0x1680 || (point >= 0x2000 && point <= 0x200a) || point === 0x2028 || point === 0x2029 || point === 0x202f || point === 0x205f || point === 0x3000 || point === 0xfeff; }
  function validField(value, maxBytes, maxPoints, rejectPipe) {
    if (typeof value !== 'string' || utf8Bytes(value) < 1 || utf8Bytes(value) > maxBytes) return false;
    var nonSpace = false;
    var points = scanCodePoints(value, function (point, count) {
      if (point < 0x20 || point === 0x7f || (rejectPipe && point === 0x7c) || (maxPoints && count >= maxPoints)) return false;
      if (!unicodeSpace(point)) nonSpace = true;
      return true;
    });
    return points >= 0 && nonSpace;
  }
  function validName(value) { return validField(value, LIMIT.displayNameBytes, LIMIT.displayNameCodePoints, true); }
  function validLocus(value) { return validField(value, LIMIT.locusNameBytes, 0, true); }
  function validId(value) { return validField(value, LIMIT.idBytes, 0, true); }
  function validLocusId(value) {
    if (typeof value !== 'string' || !/^(0|-?[1-9][0-9]{0,18})$/.test(value)) return false;
    var negative = value.charAt(0) === '-', magnitude = negative ? value.slice(1) : value;
    return magnitude.length < 19 || magnitude <= (negative ? '9223372036854775808' : '9223372036854775807');
  }
  function integer(value, min, max) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value && value >= min && value <= max ? value : null; }
  function decimal(value, min, max) { return typeof value === 'string' && /^\d+$/.test(value) && integer(Number(value), min, max) !== null ? Number(value) : null; }
  function fold(value) { return String(value).toLocaleLowerCase(); }
  function profileNameKey(value) { return typeof value === 'string' && scanCodePoints(value) >= 0 ? fold(value) : null; }
  function truncatePoints(value, limit) {
    value = String(value); var end = 0, count = 0;
    while (end < value.length && count++ < limit) end += value.charCodeAt(end) >= 0xd800 && value.charCodeAt(end) <= 0xdbff ? 2 : 1;
    return value.slice(0, end);
  }
  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  var nextId = 0;
  function newId() { return 'p' + Date.now().toString(36) + (++nextId).toString(36) + Math.floor(Math.random() * 0x100000).toString(36); }
  function presetFor(name) {
    var value = fold(name);
    if (/(walk|hik|trek|wander|wandern|gehen|spazier|marche|randonn|caminar|sender|passegg|escurs|caminh|徒步|步行|健走)/.test(value)) return [1,3,10,11,5,22];
    if (/(run|jogg|lauf|course|correr|corsa|corrida|跑步|慢跑)/.test(value)) return [1,3,8,9,11,22];
    if (/(cycl|bike|bicycle|rad|fahrrad|rennrad|vélo|bicic|cicl|mtb|自行车|單車|單車|騎行)/.test(value)) return [1,3,5,6,7,22];
    return [1,3,5,6,10,22];
  }
  function emptyPage() { return {id:newId(),type:'metrics',name:null,metrics:[]}; }
  function defaultPages(activityName) {
    var pages=[{id:newId(),type:'metrics',name:null,metrics:presetFor(activityName)}];
    if (/(walk|hik|trek|wander|wandern|gehen|spazier|marche|randonn|caminar|sender|passegg|escurs|caminh|徒步|步行|健走)/.test(fold(activityName))) pages.push({id:newId(),type:'metrics',name:null,metrics:[23]});
    while(pages.length<LIMIT.pages)pages.push(emptyPage());
    return pages;
  }
  function defaultPage(activityName) { return defaultPages(activityName)[0]; }
  function defaultsFor() { return {schema: 4, theme: 'dark', watchHrToLocus: false, heartRateIntervalSeconds: 5, activities: []}; }
  var defaults = defaultsFor('en');
  function legacyFromWire(wire) {
    try {
      var lines = String(wire).split('\n'), header = lines.shift().split('|');
      if (header.length < 2 || (header[0] !== 'dark' && header[0] !== 'light')) return null;
      var config = defaultsFor();
      config.theme = header[0]; config.watchHrToLocus = header[2] === '1';
      config.heartRateIntervalSeconds = Number(header[3] || 5);
      lines.forEach(function (line) {
        var item = line.split('|'); if (item.length < 4) throw new Error('legacy');
        config.activities.push({locusId: '', locusName: item[1], pages: [{type:'metrics',name:item[0],metrics:item[3].split(',').map(Number),id:item[4]||newId()}]});
      });
      config.legacy = true; return config;
    } catch (_) { return null; }
  }
  function migrate(config) {
    if (typeof config === 'string') return legacyFromWire(config);
    if (!config || typeof config !== 'object') return config;
    if (Array.isArray(config.profiles)) {
      var old = defaultsFor(); old.theme = config.theme || 'dark'; old.watchHrToLocus = config.watchHrToLocus === true;
      old.heartRateIntervalSeconds = Number(config.heartRateIntervalSeconds || 5); old.legacy = true;
      config.profiles.forEach(function (profile) { old.activities.push({locusId:'',locusName:profile.locus,pages:[{id:profile.id||newId(),type:'metrics',name:profile.name,metrics:profile.metrics}]}); });
      return old;
    }
    if (!Array.isArray(config.activities)) config.activities = [];
    if(config.schema===2||config.schema===undefined||config.legacy){
      config.activities.forEach(function(a){if(!Array.isArray(a.pages))a.pages=[];a.pages=a.pages.slice(0,LIMIT.pages).map(function(p){return{id:p.id||newId(),type:'metrics',name:p.name===undefined?null:p.name,metrics:Array.isArray(p.metrics)?p.metrics:[]};});while(a.pages.length<LIMIT.pages)a.pages.push(emptyPage());});
    }
    config.activities.forEach(function(a){if(typeof a.watchStepsToLocus!=='boolean')a.watchStepsToLocus=false;});config.schema=4;return config;
  }
  function validatePage(page) {
    if (!page || page.type!=='metrics' || !validId(page.id) || !(page.name===null||validName(page.name)) || !Array.isArray(page.metrics) || page.metrics.length > LIMIT.metrics) return false;
    var seen = {};
    return page.metrics.every(function (metric) { if (integer(metric, 1, 23) === null || seen[metric]) return false; seen[metric] = true; return true; });
  }
  function validate(config, allowLegacy) {
    config = migrate(config);
    if (!config || config.schema !== 4 || (config.theme !== 'dark' && config.theme !== 'light') || typeof config.watchHrToLocus !== 'boolean' || integer(config.heartRateIntervalSeconds, 1, 60) === null || !Array.isArray(config.activities)) return false;
    var activityIds = {}, pageIds = {};
    return config.activities.every(function (activity) {
      if (!activity || typeof activity.watchStepsToLocus !== 'boolean' || (!validLocusId(activity.locusId) && !(allowLegacy && activity.locusId === '')) || !validLocus(activity.locusName) || activityIds[activity.locusId] || !Array.isArray(activity.pages) || activity.pages.length !== LIMIT.pages || !activity.pages.some(function(p){return p&&p.metrics&&p.metrics.length;})) return false;
      activityIds[activity.locusId] = true;
      return activity.pages.every(function (page) {if(!validatePage(page)||pageIds[page.id])return false;pageIds[page.id]=true;return true;});
    });
  }
  function canonicalObject(config) {
    return {schema:4,theme:config.theme,watchHrToLocus:config.watchHrToLocus,heartRateIntervalSeconds:config.heartRateIntervalSeconds,activities:config.activities.map(function(a){return{locusId:a.locusId,locusName:a.locusName,watchStepsToLocus:a.watchStepsToLocus,pages:a.pages.map(function(p){return{id:p.id,type:p.type,name:p.name,metrics:p.metrics.slice()};})};})};
  }
  function serialize(config) { config = migrate(config); if (!validate(config, false)) throw new Error('Invalid configuration'); return JSON.stringify(canonicalObject(config)); }
  function parse(raw) {
    if (typeof raw !== 'string' || !raw) return null; var config;
    try { config = raw.charAt(0) === '{' ? JSON.parse(raw) : legacyFromWire(raw); } catch (_) { return null; }
    config = migrate(config); return validate(config, !!config.legacy) ? config : null;
  }
  function profilePayload(payload) {
    if (typeof payload !== 'string' || utf8Bytes(payload) > LIMIT.profileListBytes || !payload) return null;
    var ids = {}, profiles = [], lines = payload.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var separator = lines[i].indexOf('|');
      if (separator < 1 || lines[i].indexOf('|', separator + 1) >= 0) return null;
      var id = lines[i].slice(0, separator), name = lines[i].slice(separator + 1);
      if (!validLocusId(id) || !validLocus(name) || ids[id]) return null;
      ids[id] = true; profiles.push({id:id,name:name});
    }
    return profiles;
  }
  function reconcile(config, catalog, lang) {
    config = clone(migrate(config) || defaultsFor(lang));
    if (!Array.isArray(catalog) || !catalog.length || catalog.some(function (item) { return !item || !validLocusId(item.id) || !validLocus(item.name); })) return {config:config,changed:false,authoritative:false};
    var catalogIds = {};
    for (var catalogIndex = 0; catalogIndex < catalog.length; catalogIndex++) {
      if (catalogIds[catalog[catalogIndex].id]) {
        return {config:config,changed:false,authoritative:false};
      }
      catalogIds[catalog[catalogIndex].id] = true;
    }
    var previous = JSON.stringify(config), byId = {}, used = {};
    config.activities.forEach(function (a) { if (validLocusId(a.locusId)) byId[a.locusId] = a; });
    if (config.legacy) {
      config.activities.forEach(function (a) {
        if (a.locusId) return;
        var matches = catalog.filter(function (item) { return item.name === a.locusName; });
        if (matches.length !== 1) matches = catalog.filter(function (item) { return fold(item.name) === fold(a.locusName); });
        if (matches.length === 1 && !byId[matches[0].id]) { a.locusId = matches[0].id; byId[a.locusId] = a; }
      });
      delete config.legacy;
    }
    var activities = [];
    catalog.forEach(function (item) {
      if (used[item.id]) return; used[item.id] = true;
      var a=byId[item.id];if(!a)a={locusId:item.id,locusName:item.name,watchStepsToLocus:false,pages:defaultPages(item.name)};
      a.locusName=item.name;if(!a.pages||!a.pages.length)a.pages=defaultPages(item.name);while(a.pages.length<LIMIT.pages)a.pages.push(emptyPage());activities.push(a);
    });
    config.activities = activities;
    return {config:config,changed:previous !== JSON.stringify(config),authoritative:true};
  }
  function activity(config, locusId) { return config.activities.filter(function (item) { return item.locusId === locusId; })[0] || null; }
  function add(config,locusId,index,metric){var g=activity(config,locusId),p=g&&g.pages[index];if(!p||p.metrics.length>=LIMIT.metrics||integer(metric,1,23)===null||p.metrics.indexOf(metric)>=0)return false;p.metrics.push(metric);return true;}
  function remove(config,locusId,index,metricIndex){var g=activity(config,locusId),p=g&&g.pages[index];if(!p||!p.metrics[metricIndex]||p.metrics.length===1&&g.pages.filter(function(x){return x.metrics.length;}).length===1)return false;p.metrics.splice(metricIndex,1);return true;}
  function rename(config,locusId,index,name){var g=activity(config,locusId),p=g&&g.pages[index];if(!p||!(name===null||validName(name)))return false;p.name=name;return true;}
  function move(config,locusId,from,to){var group=activity(config,locusId);if(!group||from<0||to<0||from>=group.pages.length||to>=group.pages.length)return false;group.pages.splice(to,0,group.pages.splice(from,1)[0]);return true;}
  function moveMetric(config,locusId,pageIndex,from,to){var g=activity(config,locusId),p=g&&g.pages[pageIndex];if(!p||from<0||to<0||from>=p.metrics.length||to>=p.metrics.length)return false;p.metrics.splice(to,0,p.metrics.splice(from,1)[0]);return true;}
  function resetActivity(config,locusId){var g=activity(config,locusId);if(!g)return false;g.watchStepsToLocus=false;g.pages=defaultPages(g.locusName);return true;}
  function resetGeneral(config){config.theme='dark';config.watchHrToLocus=false;config.heartRateIntervalSeconds=5;return config;}
  function resetLibrary(catalog,lang){return reconcile(defaultsFor(lang),catalog,lang);}
  function page(config,locusId,index){var group=activity(config,locusId);return group&&group.pages[index]?clone(group.pages[index]):null;}
  function hashBytes(value){var bytes=unescape(encodeURIComponent(value)),fnv=2166136261>>>0,crc=0xffffffff;for(var i=0;i<bytes.length;i++){var b=bytes.charCodeAt(i);fnv^=b;fnv=Math.imul(fnv,16777619)>>>0;crc^=b;for(var bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}return{a:fnv>>>0,b:(~crc)>>>0};}
  function automaticName(lang,index){return strings[locale(lang)].page+' '+index;}
  function fingerprints(config,lang){return hashBytes(serialize(config)+'|'+locale(lang));}
  function projection(config,locusId,lang){var group=activity(config,locusId);if(!group)return null;var fp=fingerprints(config,lang),active=group.pages.filter(function(p){return p.metrics.length;});return[config.theme,config.watchHrToLocus?'1':'0',config.heartRateIntervalSeconds,locusId,fp.a,fp.b,group.watchStepsToLocus?'1':'0'].join('|')+'\n'+active.map(function(p,i){return[p.name===null?automaticName(lang,i+1):p.name,p.metrics.join(','),p.id].join('|');}).join('\n');}
  function chunks(payload,size){var output=[],part='',bytes=0;for(var i=0;i<payload.length;i++){var ch=payload.charAt(i),first=payload.charCodeAt(i);if(first>=0xd800&&first<=0xdbff)ch+=payload.charAt(++i);var count=utf8Bytes(ch);if(bytes+count>size&&part){output.push(part);part='';bytes=0;}part+=ch;bytes+=count;}if(part||!output.length)output.push(part);return output;}
  function serialNewer(candidate,reference){var distance=(candidate-reference)&SERIAL_MASK;return distance>0&&distance<SERIAL_HALF_RANGE;}
  function DurableSerialCounter(storage,key){this.storage=storage;this.key=key;}
  DurableSerialCounter.prototype.reserve=function(){try{if(!this.storage||typeof this.storage.getItem!=='function'||typeof this.storage.setItem!=='function')return null;var raw=this.storage.getItem(this.key),value;if(raw===null)value=0;else{var previous=decimal(raw,0,SERIAL_MASK);if(previous===null||String(previous)!==raw)return null;value=(previous+1)&SERIAL_MASK;}this.storage.setItem(this.key,String(value));return this.storage.getItem(this.key)===String(value)?value:null;}catch(_){return null;}};
  function Outbox(sendFunction,maxAttempts,timeoutMillis){this.sendFunction=sendFunction;this.maxAttempts=maxAttempts||3;this.timeoutMillis=timeoutMillis===undefined?10000:timeoutMillis;this.queue=[];this.busy=false;}
  Outbox.prototype.enqueue=function(frames,done){if(!frames||!frames.length){if(done)done(false);return;}this.queue.push({frames:frames,index:0,attempts:0,done:done});this.pump();};
  Outbox.prototype.pump=function(){var self=this;if(this.busy||!this.queue.length)return;var op=this.queue[0],settled=false,timer=null,frame=op.frames[op.index];this.busy=true;function finish(ok){if(settled)return;settled=true;if(timer!==null)clearTimeout(timer);self.busy=false;if(ok){op.index++;op.attempts=0;if(op.index===op.frames.length){self.queue.shift();if(op.done)op.done(true);}}else if(++op.attempts>=self.maxAttempts){self.queue.shift();if(op.done)op.done(false);}self.pump();}if(this.timeoutMillis>0)timer=setTimeout(function(){finish(false);},this.timeoutMillis);try{this.sendFunction(frame,function(){finish(true);},function(){finish(false);});}catch(_){finish(false);}};
  function AckTracker(timeoutMillis,setTimer,clearTimer){this.timeoutMillis=timeoutMillis;this.setTimer=setTimer||setTimeout;this.clearTimer=clearTimer||clearTimeout;this.pending={};}
  AckTracker.prototype.register=function(id,callback){this.pending[id]={callback:callback,timer:null};};
  AckTracker.prototype.transport=function(id,success){var self=this,entry=this.pending[id];if(!entry)return false;if(!success){delete this.pending[id];entry.callback({kind:'transport-failed'});return false;}entry.timer=this.setTimer(function(){delete self.pending[id];entry.callback({kind:'timeout'});},this.timeoutMillis);return true;};
  AckTracker.prototype.accept=function(id,result){var entry=this.pending[id];if(!entry)return false;if(entry.timer)this.clearTimer(entry.timer);delete this.pending[id];entry.callback({kind:'result',result:result});return true;};
  function Transfer(storage,floorKey){this.floorStorage=storage||null;this.floorKey=floorKey||null;this.floorId=null;this.floorCompleted=false;this.floorBlocked=false;this.durableGenerationSeen=false;if(this.floorStorage&&this.floorKey){try{var raw=this.floorStorage.getItem(this.floorKey);if(raw!==null){var value=JSON.parse(raw),keys=value&&typeof value==='object'&&!Array.isArray(value)?Object.keys(value):[],legacy=value&&value.generation===undefined,allowed=keys.every(function(key){return key==='generation'||key==='id'||key==='completed';});if(!value||!allowed||(!legacy&&value.generation!==DURABLE_TRANSFER_GENERATION)||integer(value.id,0,SERIAL_MASK)===null||typeof value.completed!=='boolean')this.floorBlocked=true;else{this.floorId=value.id;this.floorCompleted=value.completed;this.durableGenerationSeen=!legacy;}}}catch(_){this.floorBlocked=true;}}this.reset();}
  Transfer.prototype.reset=function(){this.id=null;this.count=0;this.result=null;this.parts=[];};
  Transfer.prototype.storeFloor=function(id,completed){if(this.floorBlocked)return false;if(this.floorStorage&&this.floorKey){try{var value={id:id,completed:completed};if(this.durableGenerationSeen)value.generation=DURABLE_TRANSFER_GENERATION;var encoded=JSON.stringify(value);this.floorStorage.setItem(this.floorKey,encoded);if(this.floorStorage.getItem(this.floorKey)!==encoded){this.floorBlocked=true;return false;}}catch(_){this.floorBlocked=true;return false;}}this.floorId=id;this.floorCompleted=completed;return true;};
  Transfer.prototype.accept=function(payload){var generationValue=incoming(payload,K.generation,'TRANSFER_GENERATION'),marked=integer(generationValue,1,1)===DURABLE_TRANSFER_GENERATION;if((generationValue!==undefined&&!marked)||(this.durableGenerationSeen&&!marked))return null;var id=integer(incoming(payload,K.id,'TRANSFER_ID'),0,SERIAL_MASK),index=integer(incoming(payload,K.index,'CHUNK_INDEX'),0,LIMIT.profileChunks-1),count=integer(incoming(payload,K.count,'CHUNK_COUNT'),1,LIMIT.profileChunks),result=integer(incoming(payload,K.result,'RESULT'),0,3),data=incoming(payload,K.data,'CHUNK_DATA');if(id===null||index===null||count===null||index>=count||(result!==R.applied&&result!==R.failed)||typeof data!=='string'||utf8Bytes(data)<0||utf8Bytes(data)>LIMIT.chunkBytes){if(id!==null&&id===this.id&&(!marked||this.durableGenerationSeen))this.reset();return null;}if(marked&&!this.durableGenerationSeen){if(index!==0)return null;this.reset();this.floorId=null;this.floorCompleted=false;this.durableGenerationSeen=true;}if(index===0){if(id===this.id){if(count!==this.count||result!==this.result||this.parts[0]!==data)this.reset();return null;}if(this.floorBlocked||(this.floorId!==null&&(id===this.floorId?this.floorCompleted:!serialNewer(id,this.floorId))))return null;if(id!==this.floorId&&!this.storeFloor(id,false))return null;this.reset();this.id=id;this.count=count;this.result=result;}else if(this.id===null||id!==this.id||count!==this.count||result!==this.result){if(id===this.id)this.reset();return null;}if(this.parts[index]!==undefined){if(this.parts[index]!==data)this.reset();return null;}this.parts[index]=data;for(var i=0;i<count;i++)if(this.parts[i]===undefined)return null;var value=this.parts.join(''),bytes=utf8Bytes(value),complete={id:id,result:this.result,payload:value};if(bytes<0||bytes>LIMIT.profileListBytes||!this.storeFloor(id,true))return null;this.reset();return complete;};
  function incoming(payload,key,name){return payload[key]!==undefined?payload[key]:payload[name];}
  function compatibleEnvelope(payload,type){return integer(incoming(payload,K.v,'PROTOCOL_VERSION'),V,V)===V&&incoming(payload,K.release,'APP_VERSION')===RELEASE&&integer(incoming(payload,K.type,'MESSAGE_TYPE'),type,type)===type;}
  function validHeartRateMessage(payload){return compatibleEnvelope(payload,M.heartRate)&&integer(incoming(payload,K.session,'SESSION_ID'),0,0xffffffff)!==null&&integer(incoming(payload,K.sequence,'HEART_RATE_SEQUENCE'),0,0xffffffff)!==null&&integer(incoming(payload,K.time,'SAMPLE_EPOCH_SECONDS'),0,0xffffffff)!==null&&integer(incoming(payload,K.hr,'CURRENT_HEART_RATE'),25,250)!==null;}
  function configResultMessage(payload){if(!compatibleEnvelope(payload,M.configResult))return null;var id=integer(incoming(payload,K.id,'TRANSFER_ID'),0,SERIAL_MASK),result=integer(incoming(payload,K.result,'RESULT'),0,9);return id===null?null:{id:id,result:result};}
  var outbox,acks,counter;
  function runtimeOutbox(){if(!outbox)outbox=new Outbox(function(frame,success,failure){Pebble.sendAppMessage(frame,success,failure);});return outbox;}
  function runtimeAcks(){if(!acks)acks=new AckTracker(LIMIT.ackTimeoutMillis);return acks;}
  function runtimeCounter(){if(!counter)counter=new DurableSerialCounter(localStorage,CONFIG_TRANSFER_SERIAL);return counter;}
  function send(config,locusId,done){var lang=watchLanguage(),wire=projection(config,locusId,lang);if(wire===null)return null;var parts=chunks(wire,LIMIT.chunkBytes),id=runtimeCounter().reserve(),fp=fingerprints(config,lang);if(id===null){if(done)done(false);return null;}var frames=parts.map(function(part,index){var frame={};frame[K.v]=V;frame[K.release]=RELEASE;frame[K.type]=M.configChunk;frame[K.index]=index;frame[K.count]=parts.length;frame[K.data]=part;frame[K.id]=id;frame[K.generation]=DURABLE_TRANSFER_GENERATION;frame[K.fingerprintA]=fp.a;frame[K.fingerprintB]=fp.b;return frame;});runtimeAcks().register(id,function(outcome){if(done)done(outcome.kind==='result'&&(outcome.result===R.applied||outcome.result===R.queued));});runtimeOutbox().enqueue(frames,function(ok){runtimeAcks().transport(id,ok);});return id;}
  function readConfig(){try{return parse(localStorage.getItem(CONFIG))||defaultsFor(watchLanguage());}catch(_){return defaultsFor(watchLanguage());}}
  function storeConfig(config){var previous=null;try{previous=localStorage.getItem(CONFIG);var wire=serialize(config);localStorage.setItem(CONFIG,wire);if(localStorage.getItem(CONFIG)!==wire)throw new Error('storage');return true;}catch(_){try{if(previous===null)localStorage.removeItem(CONFIG);else localStorage.setItem(CONFIG,previous);}catch(__){}return false;}}
  function readCache(){try{var cache=JSON.parse(localStorage.getItem(CACHE)||'null');return cache&&cache.protocol===V&&cache.release===RELEASE&&Array.isArray(cache.profiles)?cache:null;}catch(_){return null;}}
  function writeCache(profiles){try{localStorage.setItem(CACHE,JSON.stringify({protocol:V,release:RELEASE,profiles:profiles,updated:Date.now()}));}catch(_){}}
  function watchLanguage(){try{return locale(Pebble.getActiveWatchInfo().language);}catch(_){return'en';}}
  function watchSupportsHeartRate(){try{return Pebble.getActiveWatchInfo().platform==='emery';}catch(_){return false;}}
  function requestProfiles(){var frame={};frame[K.v]=V;frame[K.release]=RELEASE;frame[K.type]=M.requestProfiles;runtimeOutbox().enqueue([frame]);}
  var catalog=null,catalogState='unavailable',activeLocusId=null,transfer=new Transfer(typeof localStorage==='undefined'?null:localStorage,PROFILE_TRANSFER_FLOOR),pendingOpen=false,openTimer=null;
  function projectionNeeded(watchA,watchB,canonical){return(watchA===0&&watchB===0)||watchA!==canonical.a||watchB!==canonical.b;}
  function fingerprintsDiffer(before,after){return before.a!==after.a||before.b!==after.b;}
  function acceptCatalog(profiles){catalog=profiles;catalogState='fresh';writeCache(profiles);var lang=watchLanguage(),before=readConfig(),beforeFp=fingerprints(before,lang),result=reconcile(before,profiles,lang),effective=result.config;if(result.authoritative&&!storeConfig(result.config)){try{localStorage.setItem(NOTICE,'storage');}catch(_){}effective=readConfig();}var afterFp=fingerprints(effective,lang);if(fingerprintsDiffer(beforeFp,afterFp)&&activeLocusId&&activity(effective,activeLocusId))send(effective,activeLocusId);if(pendingOpen)openSettings();}
  function safe(value){return String(value).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function encodedSettingsPage(config,profiles,lang,state,notice){
    var l=locale(lang),t=strings[l],mn=metricNames[l],data=encodeURIComponent(JSON.stringify(config)),cat=encodeURIComponent(JSON.stringify(profiles||[]));
    return 'data:text/html;charset=utf-8,'+encodeURIComponent('<!doctype html><html lang="'+l+'"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:16px sans-serif;margin:0;background:#f3f3f3;color:#171717}header,.bar{position:sticky;top:0;background:#111;color:#fff;padding:14px;z-index:2}section{background:#fff;margin:10px;padding:12px;border-radius:8px}h2{margin:0 0 8px}.page,.metric{display:flex;gap:8px;align-items:center;border-top:1px solid #ddd;padding:9px 0}.page .name{flex:1;text-align:left;background:none;border:0;font:inherit}.handle{font-size:22px;touch-action:none}.hidden{display:none}label{display:block;margin:10px 0}input,select,button{font:inherit;padding:8px}input,select{max-width:100%;box-sizing:border-box}.bar{bottom:0;top:auto;display:flex;gap:8px}.bar button{flex:1}.note{padding:10px;color:#555}</style><body><header><b>'+safe(t.title)+'</b></header><div class="note">'+safe(notice||t[state]||'')+'</div><main id="overview"><section><label>'+safe(t.theme)+' <select id="theme"><option value="dark">'+safe(t.dark)+'</option><option value="light">'+safe(t.light)+'</option></select></label><div class="heart-rate-settings"><label><input id="watchHr" type="checkbox"> '+safe(t.sendHr)+'</label><label>'+safe(t.hrInterval)+' <input id="interval" type="number" min="1" max="60"> '+safe(t.seconds)+'</label></div></section><div id="groups"></div></main><main id="editor" class="hidden"><section><label>'+safe(t.name)+'<br><input id="pageName"></label><label>'+safe(t.mapping)+'<br><select id="mapping"></select></label><h2>'+safe(t.metrics)+'</h2><div id="metrics"></div><button id="addMetric">'+safe(t.addMetric)+'</button></section><div class="bar"><button id="editorCancel">'+safe(t.cancel)+'</button><button id="editorDone">'+safe(t.done)+'</button></div></main><div id="mainBar" class="bar"><button id="cancel">'+safe(t.cancel)+'</button><button id="save">'+safe(t.save)+'</button></div><script>var c=JSON.parse(decodeURIComponent("'+data+'")),catalog=JSON.parse(decodeURIComponent("'+cat+'")),T='+JSON.stringify(t)+',MN='+JSON.stringify(mn)+',editing=null,draft=null;function q(id){return document.getElementById(id)}function group(id){return c.activities.filter(function(a){return a.locusId===id})[0]}function fold(s){return String(s).toLocaleLowerCase()}function newId(){return"p"+Date.now().toString(36)+Math.random().toString(36).slice(2,9)}function preset(n){n=fold(n);if(/(walk|hik|trek|wander|wandern|gehen|spazier)/.test(n))return[1,3,10,11,5,22];if(/(run|jogg|lauf)/.test(n))return[1,3,8,9,11,22];if(/(cycl|bike|bicycle|rad|fahrrad|rennrad|mtb)/.test(n))return[1,3,5,6,7,22];return[1,3,5,6,10,22]}function defaultPage(a){return{id:newId(),name:T.defaultName,metrics:preset(a.locusName)}}function unique(g,n){var base=n,i=2;while(g.pages.some(function(p){return fold(p.name)===fold(n)}))n=base+" "+i++;return n}function drag(el,arr,index,redraw){var start=0;el.onpointerdown=function(e){start=e.clientY;el.setPointerCapture(e.pointerId)};el.onpointerup=function(e){var to=Math.max(0,Math.min(arr.length-1,index+Math.round((e.clientY-start)/46)));arr.splice(to,0,arr.splice(index,1)[0]);redraw()}}function draw(){q("theme").value=c.theme;q("watchHr").checked=c.watchHrToLocus;q("interval").value=c.heartRateIntervalSeconds;var box=q("groups");box.innerHTML="";c.activities.slice().sort(function(a,b){return a.locusName.localeCompare(b.locusName)}).forEach(function(a){var s=document.createElement("section"),h=document.createElement("h2");h.textContent=a.locusName;s.appendChild(h);a.pages.forEach(function(p,i){var r=document.createElement("div");r.className="page";var handle=document.createElement("button");handle.className="handle";handle.textContent="☰";handle.setAttribute("aria-label",T.drag);drag(handle,a.pages,i,draw);r.appendChild(handle);var n=document.createElement("button");n.className="name";n.textContent=p.name;n.onclick=function(){openEditor(a.locusId,i)};r.appendChild(n);var cp=document.createElement("button");cp.textContent="⧉";cp.onclick=function(){if(a.pages.length>=4)return;var x=JSON.parse(JSON.stringify(p));x.id=newId();x.name=unique(a,x.name+T.copySuffix);a.pages.splice(i+1,0,x);draw()};r.appendChild(cp);var del=document.createElement("button");del.textContent="−";del.onclick=function(){if(a.pages.length===1&&!confirm(T.deleteLast))return;a.pages.splice(i,1);if(!a.pages.length)a.pages.push(defaultPage(a));draw()};r.appendChild(del);s.appendChild(r)});box.appendChild(s)})}function openEditor(id,index){editing={id:id,index:index};draft=JSON.parse(JSON.stringify(group(id).pages[index]));q("overview").className="hidden";q("mainBar").className="hidden";q("editor").className="";q("pageName").value=draft.name;var m=q("mapping");m.innerHTML="";c.activities.slice().sort(function(a,b){return a.locusName.localeCompare(b.locusName)}).forEach(function(a){var o=document.createElement("option");o.value=a.locusId;o.textContent=a.locusName;o.selected=a.locusId===id;m.appendChild(o)});drawMetrics()}function drawMetrics(){var box=q("metrics");box.innerHTML="";draft.metrics.forEach(function(metric,i){var r=document.createElement("div");r.className="metric";var h=document.createElement("button");h.className="handle";h.textContent="☰";h.setAttribute("aria-label",T.drag);drag(h,draft.metrics,i,drawMetrics);r.appendChild(h);var s=document.createElement("select");MN.forEach(function(name,j){var o=document.createElement("option");o.value=j+1;o.textContent=name;o.selected=j+1===metric;s.appendChild(o)});s.onchange=function(){draft.metrics[i]=+s.value};r.appendChild(s);var d=document.createElement("button");d.textContent="−";d.onclick=function(){if(draft.metrics.length>1){draft.metrics.splice(i,1);drawMetrics()}};r.appendChild(d);box.appendChild(r)})}q("addMetric").onclick=function(){if(draft.metrics.length>=6)return;for(var i=1;i<=22;i++)if(draft.metrics.indexOf(i)<0){draft.metrics.push(i);break}drawMetrics()};function closeEditor(){q("editor").className="hidden";q("overview").className="";q("mainBar").className="bar";editing=null;draft=null;draw()}q("editorCancel").onclick=closeEditor;q("editorDone").onclick=function(){var source=group(editing.id),destination=group(q("mapping").value),name=q("pageName").value;if(!name||draft.metrics.length<1||draft.metrics.filter(function(x,i,a){return a.indexOf(x)===i}).length!==draft.metrics.length||destination.pages.some(function(p){return p.id!==draft.id&&fold(p.name)===fold(name)})){alert(T.duplicate);return}if(source!==destination&&destination.pages.length>=4){alert(T.moveFull);return}draft.name=name;if(source===destination)source.pages[editing.index]=draft;else{source.pages.splice(editing.index,1);destination.pages.push(draft);if(!source.pages.length)source.pages.push(defaultPage(source))}closeEditor()};q("cancel").onclick=function(){closeConfig("")};q("save").onclick=function(){c.theme=q("theme").value;c.watchHrToLocus=q("watchHr").checked;c.heartRateIntervalSeconds=+q("interval").value;closeConfig(encodeURIComponent(JSON.stringify(c)))};draw();</script></body></html>');
  }
  function legacySettingsPage(config,profiles,lang,state,notice,supportsHeartRate){
    var prefix='data:text/html;charset=utf-8,';
    var page=decodeURIComponent(
      encodedSettingsPage(config,profiles,lang,state,notice).slice(prefix.length));
    page=page.replace(
      '<script>',
      '<script>function closeConfig(x){if(typeof window.__pebbleConfigClose==="function")window.__pebbleConfigClose(x);else location.href="pebblejs://close#"+encodeURIComponent(x)}');
    if(supportsHeartRate===false)page=page.replace('class="heart-rate-settings"','class="heart-rate-settings hidden"');
    page=page.replace(
      '/(walk|hik|trek|wander|wandern|gehen|spazier)/',
      '/(walk|hik|trek|wander|wandern|gehen|spazier|marche|randonn|caminar|sender|passegg|escurs|caminh|徒步|步行|健走)/');
    page=page.replace(
      '/(run|jogg|lauf)/',
      '/(run|jogg|lauf|course|correr|corsa|corrida|跑步|慢跑)/');
    page=page.replace(
      '/(cycl|bike|bicycle|rad|fahrrad|rennrad|mtb)/',
      '/(cycl|bike|bicycle|rad|fahrrad|rennrad|vélo|bicic|cicl|mtb|自行车|單車|騎行)/');
    page=page.replace(
      'closeConfig(encodeURIComponent(JSON.stringify(c)))',
      'closeConfig(JSON.stringify(c))');
    page=page.replace(
      'pages.push(defaultPage(a))',
      'pages.push(defaultPage(a.locusName))');
    page=page.replace(
      'pages.push(defaultPage(source))',
      'pages.push(defaultPage(source.locusName))');
    page=page.replace(
      '<button id="save">',
      '<button id="reset">'+safe(strings[locale(lang)].reset)+'</button><button id="save">');
    page=page.replace(
      '.handle{font-size:22px;',
      '.clone{position:relative;width:48px;height:42px}.clone:before,.clone:after{content:"";position:absolute;width:13px;height:13px;border:2px solid currentColor}.clone:before{left:12px;top:8px}.clone:after{left:18px;top:14px;background:#eee}.handle{font-size:22px;');
    page=page.replace(
      'cp.textContent="⧉";',
      'cp.className="clone";cp.setAttribute("aria-label",T.clone);');
    page=page.replace(
      'function unique(g,n){var base=n,i=2;while(g.pages.some(function(p){return fold(p.name)===fold(n)}))n=base+" "+i++;return n}',
      'function scalars(s){return Array.from(String(s))}function cut(s,n){return scalars(s).slice(0,n).join("")}function goodName(s){try{return scalars(s).length>0&&scalars(s).length<=20&&unescape(encodeURIComponent(s)).length<=80&&!/[\\x00-\\x1f\\x7f|]/.test(s)&&/\\S/.test(s)}catch(_){return false}}function unique(g,n){var base=cut(n,20),i=2;n=base;while(g.pages.some(function(p){return fold(p.name)===fold(n)})){var suffix=" "+i++;n=cut(base,20-scalars(suffix).length)+suffix}return n}');
    page=page.replace(
      'if(!name||draft.metrics.length<1',
      'if(!goodName(name)||draft.metrics.length<1');
    page=page.replace(
      'c.heartRateIntervalSeconds=+q("interval").value;closeConfig(JSON.stringify(c))',
      'c.heartRateIntervalSeconds=+q("interval").value;if(c.heartRateIntervalSeconds<1||c.heartRateIntervalSeconds>60){alert(T.invalid);return}closeConfig(JSON.stringify(c))');
    page=page.replace(
      'q("cancel").onclick=',
      'q("reset").onclick=function(){if(!confirm(T.confirmReset))return;c={schema:2,theme:"dark",watchHrToLocus:false,heartRateIntervalSeconds:5,activities:catalog.map(function(a){return{locusId:a.id,locusName:a.name,pages:[defaultPage(a.name)]}})};draw()};q("cancel").onclick=');
    return prefix+encodeURIComponent(page);
  }
  function dragEnhancement(){
    return [
      '<style>.drag-floating{position:fixed!important;z-index:10;margin:0!important;opacity:.94;box-shadow:0 12px 28px rgba(15,23,42,.32);outline:2px solid var(--primary);overflow:hidden}.drag-placeholder{border:2px dashed var(--primary);border-radius:10px;background:var(--bg);margin:8px 0}.metric-placeholder{margin-top:8px}.page.drop-target{outline:2px solid var(--primary);outline-offset:2px}.page.drop-unavailable{opacity:.55;outline:2px dashed var(--border);outline-offset:2px}.drag-message{margin:0 4px 8px;color:var(--text);font-size:14px}.handle[aria-pressed=true]{background:var(--primary);color:var(--on-primary)}.move-menu{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:8px 12px 12px 56px;padding:8px;border:1px solid var(--primary);border-radius:8px;background:var(--bg)}.metric+.move-menu{margin:8px 0}.move-menu .wide{grid-column:1/-1}.move-menu button{width:100%;text-align:left}.move-menu button:disabled{opacity:.55}</style>',
      '<script>(function(){',
      'var drag=null,keyboard=null,menu=null,ignoreMouseUntil=0,EDGE=48,THRESHOLD=6;',
      'var message=document.createElement("p");message.id="dragMessage";message.className="drag-message hidden";q("pages").parentNode.insertBefore(message,q("pages"));',
      'function report(text,visible){q("reorderStatus").textContent=text||"";message.textContent=text||"";message.className=visible&&text?"drag-message":"drag-message hidden"}',
      'function point(e){var list=e.touches&&e.touches.length?e.touches:e.changedTouches&&e.changedTouches.length?e.changedTouches:null,p=list?list[0]:e;return{x:p.clientX||0,y:p.clientY||0}}',
      'function pages(){return Array.prototype.slice.call(q("pages").children).filter(function(x){return x.classList.contains("page")})}',
      'function pageById(id){for(var i=0;i<draft.pages.length;i++)if(draft.pages[i].id===id)return draft.pages[i];return null}',
      'function pageIndex(id){for(var i=0;i<draft.pages.length;i++)if(draft.pages[i].id===id)return i;return-1}',
      'function direct(parent,kind){return Array.prototype.slice.call(parent.children).filter(function(x){return x.classList.contains(kind)&&(!drag||x!==drag.row)})}',
      'function clearTargets(){pages().forEach(function(x){x.classList.remove("drop-target","drop-unavailable")})}',
      'function nearestPage(y){var list=pages(),best=null,distance=Infinity;list.forEach(function(x){var r=x.getBoundingClientRect(),d=y<r.top?r.top-y:y>r.top+r.height?y-r.top-r.height:0;if(d<distance){distance=d;best=x}});return best}',
      'function placeBefore(parent,node,before){parent.insertBefore(node,before||null)}',
      'function movePlaceholder(p){clearTargets();if(drag.kind==="page"){var list=direct(q("pages"),"page"),before=null;for(var i=0;i<list.length;i++){var r=list[i].getBoundingClientRect();if(p.y<r.top+r.height/2){before=list[i];break}}placeBefore(q("pages"),drag.placeholder,before);var children=Array.prototype.slice.call(q("pages").children),to=0;for(i=0;i<children.length&&children[i]!==drag.placeholder;i++)if(children[i].classList.contains("page")&&children[i]!==drag.row)to++;drag.targetIndex=to;drag.valid=true;return}',
      'var card=nearestPage(p.y);if(!card)return;var destination=pageById(card.dataset.pageId),source=pageById(drag.pageId),reason="";if(destination!==source&&destination.metrics.length>=6)reason=T.pageFull;else if(destination!==source&&destination.metrics.indexOf(drag.value)>=0)reason=T.metricUsed;if(reason){drag.valid=false;drag.reason=reason;card.classList.add("drop-unavailable");placeBefore(drag.row.parentNode,drag.placeholder,drag.row);report(reason,true);return}card.classList.add("drop-target");report("",false);var body=card.querySelector(".page-body"),rows=direct(body,"metric"),beforeMetric=null;for(var m=0;m<rows.length;m++){var mr=rows[m].getBoundingClientRect();if(p.y<mr.top+mr.height/2){beforeMetric=rows[m];break}}placeBefore(body,drag.placeholder,beforeMetric||body.querySelector(".add"));var children=Array.prototype.slice.call(body.children),target=0;for(m=0;m<children.length&&children[m]!==drag.placeholder;m++)if(children[m].classList.contains("metric")&&children[m]!==drag.row)target++;drag.targetPageId=destination.id;drag.targetIndex=target;drag.valid=true;drag.reason=""}',
      'function positionFloating(p){drag.last=p;drag.row.style.left=Math.round(p.x-drag.offsetX)+"px";drag.row.style.top=Math.round(p.y-drag.offsetY)+"px";movePlaceholder(p)}',
      'function scrollStep(){if(!drag||!drag.active)return;var y=drag.last.y,height=window.innerHeight||document.documentElement.clientHeight,amount=0;if(y<EDGE)amount=-Math.min(16,Math.ceil((EDGE-y)/3));else if(y>height-EDGE)amount=Math.min(16,Math.ceil((y-height+EDGE)/3));if(amount){window.scrollBy(0,amount);movePlaceholder(drag.last)}drag.frame=(window.requestAnimationFrame||function(fn){return setTimeout(fn,16)})(scrollStep)}',
      'function activateDrag(p){var rect=drag.rect,placeholder=document.createElement("div");drag.active=true;drag.placeholder=placeholder;placeholder.className="drag-placeholder "+(drag.kind==="metric"?"metric-placeholder":"page-placeholder");placeholder.style.height=Math.max(44,rect.height)+"px";drag.row.parentNode.insertBefore(placeholder,drag.row);drag.row.classList.add("drag-floating");drag.row.style.width=Math.max(44,rect.width)+"px";drag.row.style.height=Math.max(44,rect.height)+"px";drag.button.classList.add("dragging");menu=null;report(T.grabbed+" "+drag.label,false);positionFloating(p);scrollStep()}',
      'function begin(e,input,spec,button){if(drag||keyboard||input==="mouse"&&Date.now()<ignoreMouseUntil||input==="mouse"&&e.button!==0)return;var p=point(e),row=button.closest(".reorder-row"),rect=row.getBoundingClientRect();drag={kind:spec.kind,pageIndex:spec.pageIndex,pageId:spec.pageId,metricIndex:spec.metricIndex,value:spec.value,label:spec.label,row:row,button:button,input:input,pointerId:e.pointerId,start:p,last:p,rect:rect,offsetX:p.x-rect.left,offsetY:p.y-rect.top,targetIndex:spec.metricIndex===undefined?spec.pageIndex:spec.metricIndex,targetPageId:spec.pageId,valid:true,reason:"",active:false};if(input==="pointer"&&button.setPointerCapture)try{button.setPointerCapture(e.pointerId)}catch(_){}if(e.cancelable)e.preventDefault()}',
      'function matching(input,e){return drag&&drag.input===input&&(input!=="pointer"||drag.pointerId===e.pointerId)}',
      'function move(e,input){if(!matching(input,e))return;var p=point(e),dx=p.x-drag.start.x,dy=p.y-drag.start.y;if(!drag.active&&Math.sqrt(dx*dx+dy*dy)<THRESHOLD)return;if(e.cancelable)e.preventDefault();if(!drag.active)activateDrag(p);else positionFloating(p)}',
      'function cleanup(){if(!drag)return;clearTargets();if(drag.frame){if(window.cancelAnimationFrame)window.cancelAnimationFrame(drag.frame);else clearTimeout(drag.frame)}if(drag.placeholder&&drag.placeholder.parentNode)drag.placeholder.parentNode.removeChild(drag.placeholder);drag.row.classList.remove("drag-floating");drag.row.removeAttribute("style");drag.button.classList.remove("dragging");if(drag.input==="pointer"&&drag.button.releasePointerCapture)try{drag.button.releasePointerCapture(drag.pointerId)}catch(_){}}',
      'function sameMenu(spec){return menu&&menu.kind===spec.kind&&(spec.kind==="page"?menu.pageIndex===spec.pageIndex:menu.pageId===spec.pageId&&menu.metricIndex===spec.metricIndex)}',
      'function toggleMenu(spec){if(sameMenu(spec))menu=null;else{menu={kind:spec.kind,pageIndex:spec.pageIndex,pageId:spec.pageId,metricIndex:spec.metricIndex,value:spec.value,label:spec.label};report("",false)}drawPages();if(menu)focusHandle(menu.kind==="page"?"page":"metric-"+pageIndex(menu.pageId),menu.kind==="page"?menu.pageIndex:menu.metricIndex)}',
      'function finish(e,input,cancel){if(!matching(input,e))return;if(e&&e.cancelable)e.preventDefault();if(input==="touch")ignoreMouseUntil=Date.now()+700;var state=drag;if(!state.active){cleanup();drag=null;if(!cancel)toggleMenu(state);return}var failed=!state.valid,reason=state.reason;cleanup();drag=null;if(!cancel&&!failed){if(state.kind==="page"){var moved=draft.pages.splice(state.pageIndex,1)[0];draft.pages.splice(state.targetIndex,0,moved)}else{var source=pageById(state.pageId),destination=pageById(state.targetPageId),metric=source.metrics.splice(state.metricIndex,1)[0];destination.metrics.splice(state.targetIndex,0,metric)}}drawPages();if(cancel){report(T.dragCancelled,false);return}if(failed){report(reason,true);return}if(state.kind==="page")focusHandle("page",state.targetIndex);else focusHandle("metric-"+pageIndex(state.targetPageId),state.targetIndex);announce(T.dropped+" "+state.label,state.targetIndex,state.kind==="page"?draft.pages.length:pageById(state.targetPageId).metrics.length)}',
      'function bind(button,spec){button.onclick=function(e){e.preventDefault();e.stopPropagation()};if(window.PointerEvent)button.addEventListener("pointerdown",function(e){begin(e,"pointer",spec,button)},false);button.addEventListener("touchstart",function(e){begin(e,"touch",spec,button)},false);button.addEventListener("mousedown",function(e){begin(e,"mouse",spec,button)},false)}',
      'document.addEventListener("pointermove",function(e){move(e,"pointer")},false);document.addEventListener("pointerup",function(e){finish(e,"pointer",false)},false);document.addEventListener("pointercancel",function(e){finish(e,"pointer",true)},false);document.addEventListener("mousemove",function(e){move(e,"mouse")},false);document.addEventListener("mouseup",function(e){finish(e,"mouse",false)},false);q("activityEditor").addEventListener("touchmove",function(e){move(e,"touch")},false);q("activityEditor").addEventListener("touchend",function(e){finish(e,"touch",false)},false);q("activityEditor").addEventListener("touchcancel",function(e){finish(e,"touch",true)},false);',
      'function keyboardFocus(){var key=keyboard.kind==="page"?"page":"metric-"+pageIndex(keyboard.pageId),index=keyboard.kind==="page"?keyboard.pageIndex:keyboard.metricIndex;focusHandle(key,index);var h=document.activeElement;if(h&&h.classList.contains("handle"))h.setAttribute("aria-pressed","true")}',
      'function keyboardStart(spec){menu=null;var panel=document.querySelector(".move-menu");if(panel&&panel.parentNode)panel.parentNode.removeChild(panel);keyboard={kind:spec.kind,pageIndex:spec.pageIndex,pageId:spec.pageId,metricIndex:spec.metricIndex,value:spec.value,label:spec.label,snapshot:copy(draft.pages)};keyboardFocus();report(T.grabbed+" "+spec.label,false)}',
      'function validKeyboardDestination(page,value){if(page.metrics.length>=6)return T.pageFull;if(page.metrics.indexOf(value)>=0)return T.metricUsed;return""}',
      'function keyboardMove(delta){if(keyboard.kind==="page"){var to=keyboard.pageIndex+delta;if(to<0||to>=draft.pages.length)return;draft.pages.splice(to,0,draft.pages.splice(keyboard.pageIndex,1)[0]);keyboard.pageIndex=to;drawPages();keyboardFocus();announce(keyboard.label,to,draft.pages.length);return}var pi=pageIndex(keyboard.pageId),page=draft.pages[pi],mi=keyboard.metricIndex,next=mi+delta;if(next>=0&&next<page.metrics.length){page.metrics.splice(next,0,page.metrics.splice(mi,1)[0]);keyboard.metricIndex=next;drawPages();keyboardFocus();announce(keyboard.label,next,page.metrics.length);return}var scan=pi+delta,reason="";while(scan>=0&&scan<draft.pages.length){reason=validKeyboardDestination(draft.pages[scan],keyboard.value);if(!reason)break;scan+=delta}if(scan<0||scan>=draft.pages.length){if(reason)report(reason,true);return}page.metrics.splice(mi,1);var destination=draft.pages[scan],insert=delta<0?destination.metrics.length:0;destination.metrics.splice(insert,0,keyboard.value);keyboard.pageId=destination.id;keyboard.metricIndex=insert;drawPages();keyboardFocus();announce(keyboard.label,insert,destination.metrics.length)}',
      'function keyboardFinish(cancel){var state=keyboard;if(cancel)draft.pages=copy(state.snapshot);keyboard=null;drawPages();if(cancel){report(T.dragCancelled,false);return}if(state.kind==="page")focusHandle("page",state.pageIndex);else focusHandle("metric-"+pageIndex(state.pageId),state.metricIndex);report(T.dropped+" "+state.label,false)}',
      'function menuButton(label,disabled,action,wide,reason){var b=document.createElement("button");b.type="button";b.textContent=label+(reason?" — "+reason:"");b.disabled=disabled;if(wide)b.className="wide";b.onclick=function(e){e.preventDefault();e.stopPropagation();if(!disabled)action()};return b}',
      'function menuPanel(spec){var panel=document.createElement("div");panel.className="move-menu";if(spec.kind==="page"){panel.appendChild(menuButton(T.moveUp,spec.pageIndex===0,function(){draft.pages.splice(spec.pageIndex-1,0,draft.pages.splice(spec.pageIndex,1)[0]);menu.pageIndex--;drawPages();focusHandle("page",menu.pageIndex)},false,""));panel.appendChild(menuButton(T.moveDown,spec.pageIndex===draft.pages.length-1,function(){draft.pages.splice(spec.pageIndex+1,0,draft.pages.splice(spec.pageIndex,1)[0]);menu.pageIndex++;drawPages();focusHandle("page",menu.pageIndex)},false,""));return panel}var source=pageById(spec.pageId);panel.appendChild(menuButton(T.moveUp,spec.metricIndex===0,function(){source.metrics.splice(spec.metricIndex-1,0,source.metrics.splice(spec.metricIndex,1)[0]);menu.metricIndex--;drawPages();focusHandle("metric-"+pageIndex(menu.pageId),menu.metricIndex)},false,""));panel.appendChild(menuButton(T.moveDown,spec.metricIndex===source.metrics.length-1,function(){source.metrics.splice(spec.metricIndex+1,0,source.metrics.splice(spec.metricIndex,1)[0]);menu.metricIndex++;drawPages();focusHandle("metric-"+pageIndex(menu.pageId),menu.metricIndex)},false,""));draft.pages.forEach(function(destination,pi){if(destination.id===spec.pageId)return;var reason=destination.metrics.length>=6?T.pageFull:destination.metrics.indexOf(spec.value)>=0?T.metricUsed:"";panel.appendChild(menuButton(T.moveTo+" "+T.page+" "+(pi+1),!!reason,function(){source.metrics.splice(spec.metricIndex,1);destination.metrics.push(spec.value);menu.pageId=destination.id;menu.metricIndex=destination.metrics.length-1;drawPages();focusHandle("metric-"+pi,menu.metricIndex)},true,reason))});return panel}',
      'controls=function(spec,label){var b=document.createElement("button");spec.label=label;b.className="handle";b.type="button";b.textContent="⠿";b.dataset.key=spec.kind==="page"?"page":"metric-"+pageIndex(spec.pageId);b.dataset.index=spec.kind==="page"?spec.pageIndex:spec.metricIndex;b.setAttribute("aria-label",T.drag+" "+label);b.setAttribute("aria-pressed","false");b.setAttribute("aria-haspopup","true");b.setAttribute("aria-expanded",sameMenu(spec)?"true":"false");b.onkeydown=function(e){var activation=e.key==="Enter"||e.key===" "||e.key==="Spacebar";if(activation){e.preventDefault();if(!keyboard)keyboardStart(spec);else keyboardFinish(false);return}if(e.key==="Escape"&&keyboard){e.preventDefault();keyboardFinish(true);return}if(keyboard&&(e.key==="ArrowUp"||e.key==="ArrowDown")){e.preventDefault();keyboardMove(e.key==="ArrowUp"?-1:1)}};bind(b,spec);return b};',
      'drawPages=function(){var box=q("pages");box.innerHTML="";draft.pages.forEach(function(p,pi){var card=document.createElement("section");card.className="card page reorder-row";card.dataset.pageId=p.id;var head=document.createElement("div");head.className="page-head";head.appendChild(controls({kind:"page",pageIndex:pi},T.page+" "+(pi+1)));var title=document.createElement("div");title.className="page-title";if(p.metrics.length){var input=document.createElement("input");input.className="page-name";input.value=p.name||"";input.placeholder=T.page+" "+(draft.pages.filter(function(x,i){return i<=pi&&x.metrics.length}).length);input.setAttribute("aria-label",T.customName);input.oninput=function(){p.name=input.value||null};title.appendChild(input)}else{title.textContent=T.inactivePage;title.className+=" muted"}head.appendChild(title);var badge=document.createElement("span");badge.className="badge";badge.textContent=p.metrics.length+"/6";head.appendChild(badge);card.appendChild(head);if(menu&&menu.kind==="page"&&menu.pageIndex===pi)card.appendChild(menuPanel(menu));var content=document.createElement("div");content.className="page-body";p.metrics.forEach(function(metric,mi){var row=document.createElement("div");row.className="metric reorder-row";row.appendChild(controls({kind:"metric",pageId:p.id,metricIndex:mi,value:metric},MN[metric-1]));var select=document.createElement("select");MN.forEach(function(name,i){var option=document.createElement("option"),value=i+1;option.value=value;option.textContent=name;option.selected=value===metric;option.disabled=value!==metric&&p.metrics.indexOf(value)>=0;select.appendChild(option)});select.onchange=function(){p.metrics[mi]=+select.value;drawPages()};row.appendChild(select);var remove=document.createElement("button");remove.className="remove";remove.textContent="−";remove.setAttribute("aria-label",T.remove);remove.onclick=function(){if(p.metrics.length===1&&activeCount()===1){alert(T.finalPage);return}p.metrics.splice(mi,1);drawPages()};row.appendChild(remove);content.appendChild(row);if(menu&&menu.kind==="metric"&&menu.pageId===p.id&&menu.metricIndex===mi)content.appendChild(menuPanel(menu))});var add=document.createElement("button");add.className="add";add.textContent=T.addMetric;add.disabled=p.metrics.length>=6;add.onclick=function(){for(var m=1;m<=MN.length;m++)if(p.metrics.indexOf(m)<0){p.metrics.push(m);break}drawPages()};content.appendChild(add);card.appendChild(content);box.appendChild(card)});if(keyboard)keyboardFocus()};',
      'function resetInteraction(){if(drag){cleanup();drag=null}keyboard=null;menu=null;clearTargets();report("",false);var panels=document.querySelectorAll(".move-menu");for(var i=0;i<panels.length;i++)panels[i].parentNode.removeChild(panels[i])}',
      'var originalOpenActivity=openActivity,originalCloseActivity=closeActivity,originalReset=q("activityReset").onclick;openActivity=function(id){resetInteraction();originalOpenActivity(id)};closeActivity=function(commit){resetInteraction();originalCloseActivity(commit)};q("activityReset").onclick=function(e){resetInteraction();originalReset.call(this,e)};',
      'document.addEventListener("click",function(e){if(!menu||e.target.closest(".move-menu")||e.target.closest(".handle"))return;menu=null;var panel=document.querySelector(".move-menu");if(panel&&panel.parentNode)panel.parentNode.removeChild(panel)},false);document.addEventListener("keydown",function(e){if(e.key!=="Escape"||!menu||keyboard)return;e.preventDefault();var state=menu;menu=null;drawPages();focusHandle(state.kind==="page"?"page":"metric-"+pageIndex(state.pageId),state.kind==="page"?state.pageIndex:state.metricIndex)},false);',
      '})();</script>'
    ].join('');
  }
  function activityStepsEditor(page){
    var replacements=[
      [
        'q("activityName").textContent=editing.locusName;drawPages();show("activityEditor")',
        'q("activityName").textContent=editing.locusName;q("watchSteps").checked=!!draft.watchStepsToLocus;q("watchSteps").onchange=function(){if(draft)draft.watchStepsToLocus=q("watchSteps").checked};drawPages();show("activityEditor")'
      ],
      [
        'function closeActivity(commit){if(commit)editing.pages=draft.pages;',
        'function closeActivity(commit){if(commit){editing.pages=draft.pages;editing.watchStepsToLocus=!!draft.watchStepsToLocus}'
      ],
      [
        'q("activityReset").onclick=function(){if(confirm(T.confirmActivityReset)){var ids=',
        'q("activityReset").onclick=function(){if(confirm(T.confirmActivityReset)){draft.watchStepsToLocus=false;q("watchSteps").checked=false;var ids='
      ]
    ];
    replacements.forEach(function(pair){if(page.indexOf(pair[0])<0)throw new Error('Activity editor marker missing');page=page.replace(pair[0],pair[1]);});
    return page;
  }
  function settingsPage(config,profiles,lang,state,notice,supportsHeartRate){
    var l=locale(lang),t=strings[l],mn=metricNames[l],data=encodeURIComponent(JSON.stringify(config));
    var css=':root{color-scheme:light dark;--bg:#F4FBF6;--surface:#fff;--text:#1E293B;--muted:#64748B;--border:#CBD5E1;--primary:#006C4C;--on-primary:#fff}*{box-sizing:border-box}@media(prefers-color-scheme:dark){:root{--bg:#0F172A;--surface:#1E293B;--text:#F1F5F9;--muted:#94A3B8;--border:#475569;--primary:#34D399;--on-primary:#052E24}}body{margin:0;background:var(--bg);color:var(--text);font:16px system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{padding:12px 12px 92px}.hidden{display:none!important}.top{display:flex;align-items:center;justify-content:space-between;margin:4px 0 12px}.top h1{font-size:22px;margin:0}.section-title{font-size:22px;margin:24px 4px 8px}.general-link{display:flex;width:100%;align-items:center;gap:12px;padding:8px 12px;text-align:left}.general-link .label{flex:1;font-weight:600}.general-link .gear{font-size:20px}.icon,.handle,.remove{min-width:44px;width:44px}.handle{font-size:25px;line-height:1;touch-action:none;cursor:grab}.handle.dragging{cursor:grabbing;background:var(--bg);box-shadow:0 4px 12px rgba(15,23,42,.24)}.reorder-row.dragging-row{opacity:.72;outline:2px solid var(--primary);outline-offset:2px}.reorder-row.drop-before{border-top:3px solid var(--primary)}.card{background:var(--surface);border:1px solid var(--border);border-radius:12px;margin:8px 0;padding:12px}.activity{display:flex;width:100%;align-items:center;text-align:left;gap:12px}.activity .label{flex:1}.activity small,.muted{color:var(--muted)}button,input,select{min-height:44px;border:1px solid var(--border);border-radius:8px;background:var(--surface);color:var(--text);font:inherit;padding:8px}button:disabled{opacity:.42;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--primary);outline-offset:2px}.primary{background:var(--primary);color:var(--on-primary);border-color:var(--primary)}.notice{font-size:14px;color:var(--muted);margin:0 4px 12px}.bar{position:fixed;display:flex;gap:8px;bottom:0;left:0;right:0;padding:12px;background:var(--surface);border-top:1px solid var(--border);z-index:3}.bar button{flex:1}.page{padding:0;overflow:hidden}.page-head{display:flex;align-items:center;gap:8px;padding:12px}.page-body{margin:0 12px 12px 36px;padding:0 0 0 12px;border-left:2px solid var(--border)}.page-title{flex:1;min-width:0}.page-title input{width:100%}.badge{white-space:nowrap;border-radius:999px;background:var(--bg);padding:4px 8px}.metric{display:flex;gap:8px;align-items:center;margin-top:8px}.metric select{flex:1;min-width:0}.add{width:100%;margin-top:8px}.field{display:block;margin:12px 0}.field select,.field input[type=number]{width:100%;margin-top:4px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}';
    var body='<!doctype html><html lang="'+l+'"><meta name="viewport" content="width=device-width,initial-scale=1"><style>'+css+'</style><body>'+
      '<main id="overview"><button id="generalOpen" class="card general-link"><span class="gear" aria-hidden="true">&#9881;</span><span class="label">'+safe(t.generalSettings)+'</span><span aria-hidden="true">&#8250;</span></button><h1 class="section-title">'+safe(t.activities)+'</h1><p class="notice">'+safe(notice||t[state]||'')+'</p><div id="activities"></div></main>'+
      '<main id="activityEditor" class="hidden"><div class="top"><button id="activityBack">&#8249; '+safe(t.cancel)+'</button><h1 id="activityName"></h1></div><section class="card"><label class="field"><input id="watchSteps" type="checkbox"> '+safe(t.sendSteps)+'</label></section><div id="pages"></div><button id="activityReset">'+safe(t.resetActivity)+'</button><div class="bar"><button id="activityCancel">'+safe(t.cancel)+'</button><button id="activityDone" class="primary">'+safe(t.done)+'</button></div></main>'+
      '<main id="generalEditor" class="hidden"><div class="top"><button id="generalBack">&#8249; '+safe(t.cancel)+'</button><h1>'+safe(t.generalSettings)+'</h1></div><section class="card"><label class="field">'+safe(t.theme)+'<select id="theme"><option value="dark">'+safe(t.dark)+'</option><option value="light">'+safe(t.light)+'</option></select></label><div class="heart-rate-settings'+(supportsHeartRate===false?' hidden':'')+'"><label class="field"><input id="watchHr" type="checkbox"> '+safe(t.sendHr)+'</label><label class="field">'+safe(t.hrInterval)+'<input id="interval" type="number" min="1" max="60"> '+safe(t.seconds)+'</label></div></section><button id="generalReset">'+safe(t.resetGeneral)+'</button><div class="bar"><button id="generalCancel">'+safe(t.cancel)+'</button><button id="generalDone" class="primary">'+safe(t.done)+'</button></div></main>'+
      '<div id="mainBar" class="bar"><button id="cancel">'+safe(t.cancel)+'</button><button id="save" class="primary">'+safe(t.save)+'</button></div><div id="reorderStatus" class="sr-only" aria-live="polite"></div>'+
      '<script>function closeConfig(x){if(typeof window.__pebbleConfigClose==="function")window.__pebbleConfigClose(x);else location.href="pebblejs://close#"+encodeURIComponent(x)}var c=JSON.parse(decodeURIComponent("'+data+'")),T='+JSON.stringify(t)+',MN='+JSON.stringify(mn)+',editing=null,draft=null,generalDraft=null;function q(x){return document.getElementById(x)}function copy(x){return JSON.parse(JSON.stringify(x))}function newId(){return"p"+Date.now().toString(36)+Math.random().toString(36).slice(2,10)}function show(id){["overview","activityEditor","generalEditor"].forEach(function(x){q(x).className=x===id?"":"hidden"});q("mainBar").className=id==="overview"?"bar":"hidden"}function announce(label,index,total){q("reorderStatus").textContent=label+" "+T.position+" "+(index+1)+" "+T.of+" "+total}function focusHandle(key,index){var handles=document.querySelectorAll(".handle"),h=null;for(var i=0;i<handles.length;i++)if(handles[i].dataset.key===key&&+handles[i].dataset.index===index)h=handles[i];if(h)h.focus()}function controls(a,i,draw,label,key){var b=document.createElement("button"),drag=null;b.className="handle";b.type="button";b.textContent="⠿";b.dataset.key=key;b.dataset.index=i;b.setAttribute("aria-label",T.drag+" "+label);b.onkeydown=function(e){var delta=e.key==="ArrowUp"?-1:e.key==="ArrowDown"?1:0,target=i+delta;if(!delta)return;e.preventDefault();if(target<0||target>=a.length)return;a.splice(target,0,a.splice(i,1)[0]);draw();focusHandle(key,target);announce(label,target,a.length)};b.onpointerdown=function(e){var row=b.closest(".reorder-row");drag={from:i,to:i,row:row,parent:row.parentNode};b.classList.add("dragging");row.classList.add("dragging-row");if(b.setPointerCapture)b.setPointerCapture(e.pointerId)};b.onpointermove=function(e){if(!drag)return;var rows=Array.prototype.slice.call(drag.parent.querySelectorAll(".reorder-row")),others=rows.filter(function(row){return row!==drag.row}),target=others.length;for(var x=0;x<others.length;x++){var rect=others[x].getBoundingClientRect();if(e.clientY<rect.top+rect.height/2){target=x;break}}var add=null,children=drag.parent.children;for(var y=0;y<children.length;y++)if(children[y].classList.contains("add"))add=children[y];var before=target<others.length?others[target]:add;drag.parent.insertBefore(drag.row,before||null);rows=Array.prototype.slice.call(drag.parent.querySelectorAll(".reorder-row"));drag.to=rows.indexOf(drag.row);drag.row.classList.add("drop-before")};function finish(e,cancel){if(!drag)return;if(b.releasePointerCapture)try{b.releasePointerCapture(e.pointerId)}catch(_){}var from=drag.from,to=cancel?drag.from:drag.to;drag.row.classList.remove("dragging-row","drop-before");b.classList.remove("dragging");drag=null;if(from!==to)a.splice(to,0,a.splice(from,1)[0]);draw();focusHandle(key,to);announce(label,to,a.length)}b.onpointerup=function(e){finish(e,false)};b.onpointercancel=function(e){finish(e,true)};return b}function drawOverview(){var box=q("activities");box.innerHTML="";c.activities.slice().sort(function(a,b){return a.locusName.localeCompare(b.locusName)}).forEach(function(a){var b=document.createElement("button");b.className="card activity";var count=a.pages.filter(function(p){return p.metrics.length}).length;b.innerHTML="<span class=label></span><small>"+count+" "+(count===1?T.page.toLowerCase():T.pages)+"</small><span aria-hidden=true>›</span>";b.querySelector(".label").textContent=a.locusName;b.setAttribute("aria-label",T.openActivity+" "+a.locusName);b.onclick=function(){openActivity(a.locusId)};box.appendChild(b)})}function openActivity(id){editing=c.activities.filter(function(a){return a.locusId===id})[0];draft=copy(editing);q("activityName").textContent=editing.locusName;drawPages();show("activityEditor")}function activeCount(){return draft.pages.filter(function(p){return p.metrics.length}).length}function drawPages(){var box=q("pages");box.innerHTML="";draft.pages.forEach(function(p,pi){var card=document.createElement("section");card.className="card page reorder-row";var head=document.createElement("div");head.className="page-head";head.appendChild(controls(draft.pages,pi,drawPages,T.page+" "+(pi+1),"page"));var title=document.createElement("div");title.className="page-title";if(p.metrics.length){var input=document.createElement("input");input.className="page-name";input.value=p.name||"";input.placeholder=T.page+" "+(draft.pages.filter(function(x,i){return i<=pi&&x.metrics.length}).length);input.setAttribute("aria-label",T.customName);input.oninput=function(){p.name=input.value||null};title.appendChild(input)}else{title.textContent=T.inactivePage;title.className+=" muted"}head.appendChild(title);var badge=document.createElement("span");badge.className="badge";badge.textContent=p.metrics.length+"/6";head.appendChild(badge);card.appendChild(head);var content=document.createElement("div");content.className="page-body";p.metrics.forEach(function(metric,mi){var row=document.createElement("div");row.className="metric reorder-row";row.appendChild(controls(p.metrics,mi,drawPages,MN[metric-1],"metric-"+pi));var select=document.createElement("select");MN.forEach(function(name,i){var option=document.createElement("option"),value=i+1;option.value=value;option.textContent=name;option.selected=value===metric;option.disabled=value!==metric&&p.metrics.indexOf(value)>=0;select.appendChild(option)});select.onchange=function(){p.metrics[mi]=+select.value;drawPages()};row.appendChild(select);var remove=document.createElement("button");remove.className="remove";remove.textContent="−";remove.setAttribute("aria-label",T.remove);remove.onclick=function(){if(p.metrics.length===1&&activeCount()===1){alert(T.finalPage);return}p.metrics.splice(mi,1);drawPages()};row.appendChild(remove);content.appendChild(row)});var add=document.createElement("button");add.className="add";add.textContent=T.addMetric;add.disabled=p.metrics.length>=6;add.onclick=function(){for(var m=1;m<=MN.length;m++)if(p.metrics.indexOf(m)<0){p.metrics.push(m);break}drawPages()};content.appendChild(add);card.appendChild(content);box.appendChild(card)})}function closeActivity(commit){if(commit)editing.pages=draft.pages;editing=null;draft=null;drawOverview();show("overview")}q("activityCancel").onclick=q("activityBack").onclick=function(){closeActivity(false)};q("activityDone").onclick=function(){closeActivity(true)};q("activityReset").onclick=function(){if(confirm(T.confirmActivityReset)){var ids=draft.pages.map(function(){return newId()}),n=draft.locusName.toLowerCase(),walking=/(walk|hik|trek|wander|wandern|gehen|spazier|marche|randonn|caminar|sender|passegg|escurs|caminh|徒步|步行|健走)/.test(n),metrics=walking?[1,3,10,11,5,22]:/(run|jogg|lauf|course|correr|corsa|corrida|跑步|慢跑)/.test(n)?[1,3,8,9,11,22]:/(cycl|bike|bicycle|rad|fahrrad|rennrad|vélo|bicic|cicl|mtb|自行车|單車|騎行)/.test(n)?[1,3,5,6,7,22]:[1,3,5,6,10,22];draft.pages=draft.pages.map(function(p,i){return{id:ids[i],type:"metrics",name:null,metrics:i===0?metrics.slice():walking&&i===1?[23]:[]}});drawPages()}};function openGeneral(){generalDraft={theme:c.theme,watchHrToLocus:c.watchHrToLocus,heartRateIntervalSeconds:c.heartRateIntervalSeconds};q("theme").value=generalDraft.theme;q("watchHr").checked=generalDraft.watchHrToLocus;q("interval").value=generalDraft.heartRateIntervalSeconds;show("generalEditor")}function closeGeneral(commit){if(commit){var interval=+q("interval").value;if(interval<1||interval>60){alert(T.invalid);return}c.theme=q("theme").value;c.watchHrToLocus=q("watchHr").checked;c.heartRateIntervalSeconds=interval}generalDraft=null;show("overview")}q("generalOpen").onclick=openGeneral;q("generalCancel").onclick=q("generalBack").onclick=function(){closeGeneral(false)};q("generalDone").onclick=function(){closeGeneral(true)};q("generalReset").onclick=function(){if(confirm(T.confirmGeneralReset)){q("theme").value="dark";q("watchHr").checked=false;q("interval").value=5}};q("cancel").onclick=function(){closeConfig("")};q("save").onclick=function(){closeConfig(JSON.stringify(c))};drawOverview();</script></body></html>';
    return 'data:text/html;charset=utf-8,'+encodeURIComponent(activityStepsEditor(body.replace('</body>',dragEnhancement()+'</body>')));
  }
  function openSettings(){if(openTimer){clearTimeout(openTimer);openTimer=null;}pendingOpen=false;var cached=readCache(),profiles=catalog||(cached&&cached.profiles)||[],state=catalog?catalogState:cached?'stale':'unavailable',config=catalog?reconcile(readConfig(),profiles,watchLanguage()).config:readConfig(),notice=null;try{notice=localStorage.getItem(NOTICE);localStorage.removeItem(NOTICE);}catch(_){}Pebble.openURL(settingsPage(config,profiles,watchLanguage(),state,notice==='storage'?strings[watchLanguage()].storage:null,watchSupportsHeartRate()));}
  if(typeof Pebble!=='undefined'){
    Pebble.addEventListener('ready',function(){requestProfiles();});
    Pebble.addEventListener('showConfiguration',function(){pendingOpen=true;requestProfiles();openTimer=setTimeout(openSettings,500);});
    Pebble.addEventListener('webviewclosed',function(event){if(!event.response)return;try{var config=migrate(JSON.parse(decodeURIComponent(event.response)));if(!validate(config,false))return;if(!storeConfig(config)){localStorage.setItem(NOTICE,'storage');return;}if(activeLocusId&&activity(config,activeLocusId))send(config,activeLocusId,function(ok){if(!ok)localStorage.setItem(NOTICE,'transport');});}catch(_){}});
    Pebble.addEventListener('appmessage',function(event){var payload=event&&event.payload||{},type=integer(incoming(payload,K.type,'MESSAGE_TYPE'),1,11);if(type===M.configResult){var ack=configResultMessage(payload);if(ack)runtimeAcks().accept(ack.id,ack.result);return;}if(type===M.heartRate){validHeartRateMessage(payload);return;}if(type===M.requestRuntimeConfig&&compatibleEnvelope(payload,M.requestRuntimeConfig)){var id=incoming(payload,K.locusId,'LOCUS_PROFILE_ID');if(!validLocusId(id))return;activeLocusId=id;var config=readConfig(),fp=fingerprints(config,watchLanguage()),wa=integer(incoming(payload,K.fingerprintA,'CONFIG_FINGERPRINT_A'),0,0xffffffff),wb=integer(incoming(payload,K.fingerprintB,'CONFIG_FINGERPRINT_B'),0,0xffffffff);if(activity(config,id)&&projectionNeeded(wa,wb,fp))send(config,id);return;}if(type!==M.profileChunk||!compatibleEnvelope(payload,M.profileChunk))return;var complete=transfer.accept(payload);if(!complete||complete.result!==R.applied)return;var profiles=profilePayload(complete.payload);if(profiles)acceptCatalog(profiles);});
  }
  if(typeof module!=='undefined')module.exports={VERSION:V,RELEASE:RELEASE,LIMIT:LIMIT,KEYS:K,TYPES:M,RESULTS:R,STORAGE_KEYS:{config:CONFIG,cache:CACHE,notice:NOTICE,configSerial:CONFIG_TRANSFER_SERIAL,profileFloor:PROFILE_TRANSFER_FLOOR},catalogComplete:catalogComplete,defaults:defaults,defaultsFor:defaultsFor,locale:locale,validate:validate,serialize:serialize,parse:parse,migrate:migrate,reconcile:reconcile,resetLibrary:resetLibrary,resetActivity:resetActivity,resetGeneral:resetGeneral,presetFor:presetFor,defaultPage:defaultPage,add:add,remove:remove,rename:rename,move:move,moveMetric:moveMetric,page:page,activity:activity,automaticName:automaticName,projection:projection,fingerprints:fingerprints,projectionNeeded:projectionNeeded,fingerprintsDiffer:fingerprintsDiffer,Transfer:Transfer,DurableSerialCounter:DurableSerialCounter,serialNewer:serialNewer,Outbox:Outbox,AckTracker:AckTracker,profilePayload:profilePayload,chunks:chunks,utf8Bytes:utf8Bytes,profileNameKey:profileNameKey,validName:validName,validLocus:validLocus,validLocusId:validLocusId,validId:validId,validHeartRateMessage:validHeartRateMessage,configResultMessage:configResultMessage,settingsPage:settingsPage};
})(this);
