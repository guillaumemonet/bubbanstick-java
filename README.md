# Bubba 'n' Stix — remake Java

Remake fidèle du jeu Amiga **Bubba 'n' Stix** (Core Design, 1994), en Java pur.

Le dépôt ne contient **aucun fichier du jeu original** : les graphismes, les sons, les musiques et
le code sont extraits de vos propres images de disquettes.

## Principe

Le jeu est reconstruit routine par routine à partir du désassemblage de l'exécutable original
(`Bubba`, 68000) :

1. **Extraction.** Les outils d'extraction lisent les disquettes (système de fichiers AmigaDOS),
   décompressent les fichiers (RNC ProPack 1 et 2, SPAK, NoisePacker) et convertissent tout en
   formats courants : PNG, WAV, MOD et JSON.
2. **Exécution.** Le moteur Java (fenêtre, boucle à 50 Hz, rendu, son) fait tourner la boucle de
   jeu originale. Ce qui n'est pas encore porté est exécuté par un petit interpréteur 68000
   (`bubba.cpu.M68k`).
3. **Portage.** Chaque routine originale est réécrite en Java et branchée à la place du code 68000.
   Le mode vérification compare chaque portage avec l'original, mémoire et registres, image par
   image.

Le but est de supprimer complètement l'émulation : à terme, l'interpréteur 68000 ne servira plus
que d'oracle de vérification pendant le développement.

### Avancement

**Porté en Java**, vérifié sans aucune différence sur les 5 niveaux :
- **Objets** :
  - boucle des objets ;
  - script de comportement et animations ;
  - physique et collisions avec le décor ;
  - naissance et mort des objets ;
  - gros objets (plateformes, ascenseurs, portes) ;
  - collisions entre objets solides.
- **Bubba** :
  - contacts avec les objets (bonus, dégâts) ;
  - perte d'énergie.
- **Décor** :
  - caméra ;
  - animation des blocs (lave, blocs animés par le niveau).
- **Rendu et son**, entièrement en Java :
  - décor et parallaxe ;
  - sprites et barre d'état ;
  - musiques ProTracker et bruitages.

**Encore émulé** :
- **Bubba** : sprite et machine d'états ;
- **Stix** ;
- **Barre d'état** : données calculées par le jeu ;
- **Code propre à chaque niveau** ;
- **Clavier**.

**À venir** : l'extraction aussi passe en Java, pour que le jeu lise directement les deux
`.zip` au premier lancement.

## Prérequis

- **JDK 21** (Gradle le récupère au besoin grâce au wrapper)
- les deux disquettes du jeu, au format TOSEC, disponibles par exemple sur Amiga Museum :
  - [Bubba 'n' Stix (1994)(Core)(Disk 1 of 2)[cr LTD].zip](https://amigamuseum.emu-france.info/Fichiers/ADF/B/Bubba%20'n'%20Stix%20(1994)(Core)(Disk%201%20of%202)%5bcr%20LTD%5d.zip)
  - [Bubba 'n' Stix (1994)(Core)(Disk 2 of 2)[cr LTD].zip](https://amigamuseum.emu-france.info/Fichiers/ADF/B/Bubba%20'n'%20Stix%20(1994)(Core)(Disk%202%20of%202)%5bcr%20LTD%5d.zip)

## Données du jeu

Le jeu lit ses données (images, sons, musiques, niveaux, code original) dans un dossier
`assets/`, par défaut `../assets`, ou celui donné par `-Dbubba.assets=<dossier>`.

> **En cours :** l'extraction de ces données depuis les deux `.zip` est en train d'être réécrite
> en Java, pour que le jeu les crée lui-même au premier lancement. D'ici là, le dossier
> `assets/` doit être produit par les outils d'extraction de développement, qui ne sont pas
> publiés dans ce dépôt.

## Lancer

```
gradlew run            # Linux / macOS : ./gradlew run
```
- Mode vérification (plus lent) : `gradlew run -PverifyPorts`. Chaque routine portée est
  rejouée sur le code original et toute différence est signalée.
- Le projet s'ouvre directement dans NetBeans, IntelliJ IDEA ou VS Code comme projet Gradle.

## Contrôles

| Touche | Action |
|---|---|
| Flèches / ZQSD / WASD | directions (joystick) |
| Espace / Ctrl / Entrée | feu |
| Échap | retour / quitter |
| F1–F4 | fenêtre ×1 à ×4 |
| F11 | plein écran |
| N | *(debug)* niveau suivant |
| B | *(debug)* affichage de debug |

## Organisation

```
src/main/java/bubba/
  engine/   fenêtre, boucle 50 Hz (VBL PAL), entrées, chargement des assets
  audio/    lecteur ProTracker, bruitages
  scenes/   écran titre, options, niveau
  level/    carte, blocs, parallaxe, placement des objets
  entity/   sprites, machines virtuelles d'animation et de comportement
  emu/      exécution de la boucle de jeu originale, barre d'état, sons
  game/     routines originales portées en Java (Ports = registre des portages)
  cpu/      interpréteur 68000 et mémoire (oracle de vérification)
  dev/      outils de développement : captures, couverture, fuzzing des portages
```

## Avertissement

Bubba 'n' Stix est © 1994 Core Design. Ce projet est un travail de préservation et
d'étude, sans but commercial, et n'est affilié ni à Core Design ni à ses ayants droit. Il ne
distribue aucune donnée du jeu : il faut fournir ses propres images de disquettes.
