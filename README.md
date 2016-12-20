Pharos UI
=========

This repository contains the source code for the front end user
interface (UI) [Pharos](https://pharos.nih.gov) as part of the
Knowledge Management Center (KMC) within the [Illuminating the
Druggable Genome (IDG)](http://commonfund.nih.gov/idg/index)
project. Please forward questions and/or feedback about the code to us
at [pharos@mail.nih.gov](mailto:pharos@mail.nih.gov).

Requirements
============

+ Java JDK 1.8
+ ```git```
+ (Optional) Database server (e.g., MySQL, Postgresql, Oracle,
etc.). The embedded database ```H2``` can be used if no external
database is available.
+ (Optional) The [SBT](http://www.scala-sbt.org/) build system.

Building and Running
====================

Prior to building the code, please edit the database information
(```db.default.*```) in the file ```modules/idg/conf/pharos-dev.conf```
as appropriate to match your local setup. To build the code, simply
type

```
./activator -Dconfig.file=modules/idg/conf/pharos-dev.conf idg/compile
```

Note that if you have ```SBT``` installed, you can replace
```activator``` with ```sbt```, i.e.,

```
sbt -Dconfig.file=modules/idg/conf/pharos-dev.conf idg/run
```

If all goes well, a functional version of Pharos is available at
[http://localhost:9000/idg](http://localhost:9000/idg). Of course,
this version doesn't have any data yet. To load some data, you'll
first need to have the [TCRD](http://juniper.health.unm.edu/idg-kmc/)
MySQL instance available, then visit
[http://localhost:9000/idg/tcrd](http://localhost:9000/idg/tcrd) to
load the data.

To build a self-contained distribution for production use, simply run
the following command:

```
sbt -Dconfig.file=modules/idg/conf/pharos-dev.conf idg/dist
```

If all goes well, this should create a zip file under
```modules/idg/target/universal/``` of the form

```
idg-{branch}-{date}-{commit}.zip
```

where ```{branch}``` is the current git branch (e.g., ```master```),
```{commit}``` is the 7-character git commit hash, 
and ```{date}``` is the current date. Now this self-contained zip file
can be deployed in production, e.g., 

```
unzip idg-master-20160807-1ade21f.zip
cd idg-master-20160807-1ade21f
./bin/idg -mem 8192 -Dconfig.resource=pharos-dev.conf -Dhttp.port=9000 -Djava.awt.headless=true
```

To clean up, simply issue:

```
sbt clean
sbt idg/clean
```
