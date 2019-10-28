# open-beat-control

Provides a subset of
[beat-link](https://github.com/Deep-Symmetry/beat-link#beat-link)
features over [Open Sound
Control](https://en.wikipedia.org/wiki/Open_Sound_Control).

> :construction: This project is at a very early stage of development
> and is not yet ready for widespread use.

## Background

This project is intended for people who want to use the beat-link
library to integrate with Pioneer DJ equipment, but are running in a
non-JVM environment (like [PureData](https://puredata.info) or
[Max](https://cycling74.com/products/max/)), or who need to run a
lightweight headless process (that is, with no attached monitor,
keyboard, and mouse for the GUI) on something like a Raspberry Pi, and
thus are unable to take advantage of the features of [Beat Link
Trigger](https://github.com/Deep-Symmetry/beat-link-trigger#beat-link-trigger).

To begin with, only a few features will be supported, but over time
(and based on interest and requests) more will be added as practical
approaches can be identified. The
[Carabiner](https://github.com/brunchboy/carabiner#carabiner)
integration currently provided by
[beat-carabiner](https://github.com/Deep-Symmetry/beat-carabiner#beat-carabiner)
is expected to be an early addition, and that project will turn into a
library shared by both this project and Beat Link Trigger itself, to
solve the problem of diverging features and stale code.

### Why OSC?

Open Sound Control is a strange protocol, and makes some aspects of
implementing an API awkward (there is no intrinsic support for
requests with responses), but it is widely supported in the kind of
experimental music environments that are most likely to benefit from a
project like this.

## Installation

Install a Java runtime, and the latest `open-beat-control.jar` from
the
[releases](https://github.com/Deep-Symmetry/open-beat-control/releases)
page on your target hardware.

You may be able to get by with Java 6, but a current release will
perform better and have more recent security updates.

You can either open-beat-control manually when you want to use it, or
configure it to start when your system boots.

## Usage

To start open-beat-control manually, run:

    $ java -jar open-beat-control.jar

It will log to the terminal window in which you are running it. If you
instead want to run it at system startup, you will probably also want
to set a log-file path, so it logs to a rotated log file in your
standard system logs directory, something like:

    $ java -jar open-beat-control.jar -L /var/log/open-beat-control.log

Other options allow you to specify the port number on which its OSC
server listens, and there will be more to come.

## Options

    -o, --osc-port PORT        17002  Port number of OSC server
    -L, --log-file PATH               Log to a rotated file instead of stdout
    -h, --help                        Display help information and exit

## License

Copyright © 2019 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 2.0 or (at
your option) any later version.
