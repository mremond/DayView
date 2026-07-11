# DayView

DayView rend visible le temps restant avant la fin de journée. Le cercle se consume au fil des heures afin de réduire la *time blindness*, sans transformer le temps en source de pression. Les heures de début et de fin sont réglables et conservées entre deux lancements.

## Plateformes

- Android 7.0 et versions ultérieures
- macOS 13 et versions ultérieures (application desktop native empaquetée en `.dmg`)

L’interface et la logique métier sont partagées avec Kotlin Multiplatform et Compose Multiplatform.
L’apparence claire ou sombre suit automatiquement le thème du système sur Android et macOS.
Sur Android, un widget redimensionnable affiche l’anneau et le temps restant sans ouvrir l’application. Il reprend également l’objectif global et, pendant une session Focus, l’intention et le compte à rebours en direct.
Sur macOS, DayView reste accessible depuis la barre des menus. Fermer sa fenêtre la masque sans arrêter le décompte ; le menu permet de la rouvrir ou de quitter complètement l’application.

Le mode mini-fenêtre, accessible depuis l’en-tête ou la barre des menus, garde au-dessus des autres applications une vue compacte de l’anneau et du décompte du jour. Il rappelle aussi l’objectif global et ajoute automatiquement le temps restant ainsi que l’intention lorsqu’un Focus est en cours.

## Lancer le projet

Prérequis : JDK 17 ou plus récent et Android SDK 36.

```bash
./gradlew :composeApp:run
```

### Android sur un appareil physique

Activez les **Options pour les développeurs** puis le **Débogage USB** sur l’appareil. Branchez-le, acceptez la demande d’autorisation et vérifiez qu’il apparaît avec l’état `device` :

```bash
adb devices
```

Pour compiler puis installer directement la version debug :

```bash
./gradlew :composeApp:installDebug
```

L’application peut ensuite être lancée depuis l’appareil ou en ligne de commande :

```bash
adb shell am start -n fr.dayview.app/.MainActivity
```

Pour produire l’APK sans l’installer :

```bash
./gradlew :composeApp:assembleDebug
```

L’APK est généré dans `composeApp/build/outputs/apk/debug/composeApp-debug.apk`. Il peut être installé ou mis à jour manuellement avec :

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Au premier démarrage d’un Focus, Android demande l’autorisation d’envoyer des notifications puis, selon la version du système, l’accès **Alarmes et rappels**. Ces deux autorisations sont nécessaires pour recevoir une notification sonore précise lorsque DayView n’est plus au premier plan.

Il est également possible d’ouvrir le projet dans Android Studio et de lancer la configuration `composeApp`.

### macOS

Pour générer l’image macOS :

```bash
./gradlew :composeApp:packageDmg
```

Le volume généré utilise l’icône DayView et présente l’application à côté d’un
raccourci vers `/Applications`, afin de permettre son installation par glisser-déposer.

## Icône

Le SVG maître, prévu comme référence pour les déclinaisons Android et macOS, se régénère sans dépendance externe :

```bash
python3 scripts/generate_icon_svg.py
```

Les couleurs et la taille peuvent être adaptées avec `--accent`, `--marker`, `--background`, `--surface` et `--size`. Utilisez `--help` pour afficher toutes les options.

L’icône macOS `.icns`, utilisée par le Dock et le DMG, est générée à toutes les résolutions requises depuis ce SVG :

```bash
./scripts/generate_macos_icon.sh
```

## Objectif global

Un objectif à plus long terme peut être renseigné avec une échéance au format `JJ/MM/AAAA HH:MM`. Son intitulé et son échéance sont sauvegardés localement, comme les heures de la journée. Son décompte est exprimé en heures de travail et additionne uniquement les plages comprises entre les heures quotidiennes de début et de fin.

## Temps net

Le temps net, désactivé par défaut, se configure dans l’écran Réglages. Une fois activé et l’accès au calendrier accordé (lecture seule), DayView soustrait du temps restant les plages marquées comme **occupé** dans les calendriers choisis et les grise sur le cercle. Le grand chiffre brut reste inchangé : le temps net s’affiche en information secondaire, sous le décompte. Seuls les événements « occupé » comptent ; les événements « disponible », tentatifs et journée entière sont ignorés, et les plages qui se chevauchent sont fusionnées. Sur les plateformes à pointeur, survoler un arc grisé affiche le nom de l’événement et ses horaires. La lecture reste strictement locale : seules les bornes horaires servent au calcul, le titre n’est utilisé que pour l’affichage au survol, et aucune donnée n’est envoyée sur le réseau. Sur Android, l’accès repose sur le Calendar Provider ; sur macOS, sur EventKit.

## Focus

Le minuteur Focus permet de s’engager sur un slot de 25 minutes par défaut, réglable par pas de 5 minutes. Une intention concrète doit être renseignée avant le démarrage et reste visible pendant toute la session. Son échéance et son intention sont conservées localement : le décompte continue lorsque la fenêtre est masquée ou l’application relancée. Sur Android, une alarme système déclenche une notification sonore à la fin même lorsque l’application n’est plus au premier plan. Sur macOS, le temps restant est également visible dans la barre des menus.

Pendant un Focus sur macOS, DayView observe uniquement l’identifiant de l’application au premier plan. Quatre changements d’application en moins de 45 secondes déclenchent un rappel de l’intention. Une période de grâce de 30 secondes et un délai de cinq minutes entre deux rappels évitent les interruptions répétitives. Cette détection reste locale et ne lit jamais le contenu des fenêtres.

Si DayView retrouve une session encore active après son relancement ou le réveil du Mac, un rituel de reprise remet l’intention et le temps restant au premier plan. L’utilisateur peut reprendre immédiatement ou arrêter la session.

À la fin d’un Focus, la session se clôture en un clic avec « Terminé », « Avancé » ou « À reprendre ». Ce dernier choix conserve l’intention pour la session suivante ; les deux autres libèrent le champ pour une nouvelle tâche.

## Principe du calcul

La journée utilise les heures de début et de fin choisies (08:00–18:00 par défaut) dans le fuseau local de l’appareil. Le cercle reste plein avant le début, se consume pendant la plage définie, puis atteint zéro à la fin. Les changements d’heure sont correctement pris en compte.

## Repères sonores

Les repères sonores, désactivés par défaut, se configurent dans l’écran Réglages. Hors session Focus, DayView peut jouer un bol au début de la journée, un tintement toutes les 30 à 180 minutes — toutes les demi-heures par défaut — et un gong à la fin. Pendant un Focus, ces repères de journée sont suspendus au profit de ceux de la session. Chaque repère peut être désactivé séparément, préécouté et joué au volume choisi. Les sons sont synthétisés localement et ne nécessitent aucun fichier audio ni service réseau.
