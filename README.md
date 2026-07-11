# DayView

DayView rend visible le temps restant avant la fin de journée. Le cercle se consume au fil des heures afin de réduire la *time blindness*, sans transformer le temps en source de pression. Les heures de début et de fin sont réglables et conservées entre deux lancements.

## Plateformes

- Android 7.0 et versions ultérieures
- macOS 13 et versions ultérieures (application desktop native empaquetée en `.dmg`)

L’interface et la logique métier sont partagées avec Kotlin Multiplatform et Compose Multiplatform.
L’apparence claire ou sombre suit automatiquement le thème du système sur Android et macOS.
Sur macOS, DayView reste accessible depuis la barre des menus. Fermer sa fenêtre la masque sans arrêter le décompte ; le menu permet de la rouvrir ou de quitter complètement l’application.

## Lancer le projet

Prérequis : JDK 17 ou plus récent et Android SDK 36.

```bash
./gradlew :composeApp:run
```

Pour Android, ouvrez le dossier dans Android Studio et lancez la configuration `composeApp`, ou utilisez :

```bash
./gradlew :composeApp:installDebug
```

Pour générer l’image macOS :

```bash
./gradlew :composeApp:packageDmg
```

## Icône

Le SVG maître, prévu comme référence pour les déclinaisons Android et macOS, se régénère sans dépendance externe :

```bash
python3 scripts/generate_icon_svg.py
```

Les couleurs et la taille peuvent être adaptées avec `--accent`, `--marker`, `--background`, `--surface` et `--size`. Utilisez `--help` pour afficher toutes les options.

## Objectif global

Un objectif à plus long terme peut être renseigné avec une échéance au format `JJ/MM/AAAA HH:MM`. Son intitulé et son échéance sont sauvegardés localement, comme les heures de la journée. Son décompte est exprimé en heures de travail et additionne uniquement les plages comprises entre les heures quotidiennes de début et de fin.

## Principe du calcul

La journée utilise les heures de début et de fin choisies (08:00–18:00 par défaut) dans le fuseau local de l’appareil. Le cercle reste plein avant le début, se consume pendant la plage définie, puis atteint zéro à la fin. Les changements d’heure sont correctement pris en compte.
