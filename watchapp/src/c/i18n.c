#include "i18n.h"

#include <stddef.h>
#include <string.h>

static I18nLocale s_locale = I18N_LOCALE_EN;

static const char *const s_catalog[2][I18N_STRING_COUNT] = {
  [I18N_LOCALE_EN] = {
    "Walking", "Cycling", "Config cleanup failed", "Config storage full", "Config storage error",
    "Invalid configuration", "Recording", "Paused", "Stopped", "No Locus", " | stale",
    "Phone delivery failed", "Message queue full", "Phone message dropped",
    "Profile relay unavailable", "Invalid profile list", "Config queued until stop",
    "Message queue error", "Command queue full", "Sending...", "Stop to change profile",
    "Profile was not saved", "No phone/internet", "Dictation disabled", "No speech detected",
    "Dictation failed", "Dictation unavailable", "Resume", "Pause",
    "Locus unavailable", "Profile", "Stop & save", "Add waypoint", "Add waypoint + note",
    "Controls", "Not enough memory", "Active", "Choose profile", "Save & stop",
    "Finish the recording", "Cancel", "Keep recording", "Stop recording?", "No heart rate",
    "Heart rate unavailable", "Invalid profile", "Profile not in Locus", "Invalid waypoint note",
    "Waypoint added", "Command accepted", "Command failed", "Protocol mismatch",
    "Update bridge/watch", "Command response timeout", "Messaging unavailable",
    "Elapsed", "Moving", "Distance", "Move dist", "Speed", "Average", "Max speed", "Pace",
    "Avg pace", "Altitude", "Ascent", "Descent", "Vertical", "Slope", "Avg HR", "Max HR",
    "Avg cadence", "Max cadence", "Avg power", "Max power", "Energy", "Current HR",
    "Default", "Connecting...", "No bridge response.\nInstall or open\nTrackGlance on phone.",
    "No recording.\nStart recording in\nLocus Map.",
    "Locus unavailable.\nOpen Locus Map\non the phone.", "Preparing profile...",
    "Open Watch Settings\non the phone.", "Waypoints", "Quick waypoint", "Dictated waypoint",
  },
  [I18N_LOCALE_DE] = {
    "Gehen", "Radfahren", "Konfig.-Bereinigung fehlgeschlagen", "Konfig.-Speicher voll",
    "Konfigurationsfehler", "Ungültige Konfiguration", "Aufzeichnung", "Pausiert", "Gestoppt",
    "Kein Locus", " | veraltet", "Telefon nicht erreichbar", "Nachrichtenwarteschl. voll",
    "Telefonnachricht verloren", "Profilübertr. nicht verfügbar", "Ungültige Profilliste",
    "Konfig. nach dem Stopp", "Nachrichtenfehler", "Befehlswarteschl. voll", "Senden...",
    "Zum Wechseln stoppen", "Profil nicht gespeichert", "Kein Telefon/Internet",
    "Diktat deaktiviert", "Keine Sprache erkannt", "Diktat fehlgeschlagen", "Diktat nicht verfügbar",
    "Fortsetzen", "Pausieren", "Locus nicht verfügbar", "Profil",
    "Stoppen & speichern", "Wegpunkt hinzufügen", "Wegpunkt + Notiz", "Steuerung",
    "Nicht genug Speicher", "Aktiv", "Profil wählen", "Speichern & stoppen",
    "Aufzeichnung beenden", "Abbrechen", "Weiter aufzeichnen", "Aufzeichnung stoppen?",
    "Kein Puls verfügbar", "Puls nicht verfügbar", "Ungültiges Profil", "Profil nicht in Locus",
    "Ungültige Wegpunktnotiz", "Wegpunkt hinzugefügt", "Befehl angenommen", "Befehl fehlgeschlagen",
    "Protokoll nicht kompatibel", "Bridge/Watch aktualisieren", "Befehlsantwort fehlt",
    "Nachrichten nicht verfügbar", "Gesamtzeit", "Bewegungszeit", "Strecke", "Bewegungsstr.",
    "Tempo", "Durchschnitt", "Max. Tempo", "Pace", "Ø Pace", "Höhe", "Anstieg", "Abstieg",
    "Vertikal", "Steigung", "Ø Puls", "Max. Puls", "Ø Frequenz", "Max. Frequenz",
    "Ø Leistung", "Max. Leistung", "Energie", "Aktueller Puls",
    "Standard", "Verbindung...", "Keine Bridge-Antwort.\nTrackGlance am Telefon\ninstallieren/öffnen.",
    "Keine Aufzeichnung.\nIn Locus Map\nstarten.",
    "Locus nicht verfügbar.\nLocus Map am\nTelefon öffnen.", "Profil wird vorbereitet...",
    "Watch-Einstellungen\nam Telefon öffnen.", "Wegpunkte", "Schnell-Wegpunkt", "Diktierter Wegpunkt",
  },
};

I18nLocale i18n_locale(const char *locale) {
  return locale && locale[0] == 'd' && locale[1] == 'e' ? I18N_LOCALE_DE : I18N_LOCALE_EN;
}

void i18n_set_locale(I18nLocale locale) {
  s_locale = locale == I18N_LOCALE_DE ? I18N_LOCALE_DE : I18N_LOCALE_EN;
}

I18nLocale i18n_current_locale(void) {
  return s_locale;
}

const char *i18n_text(I18nString id) {
  if (id >= I18N_STRING_COUNT) return "";
  const char *value = s_catalog[s_locale][id];
  return value ? value : "";
}

bool i18n_catalog_complete(void) {
  for (size_t locale = 0; locale < 2; locale++) {
    for (size_t id = 0; id < I18N_STRING_COUNT; id++) {
      if (!s_catalog[locale][id] || !s_catalog[locale][id][0]) return false;
    }
  }
  return true;
}
