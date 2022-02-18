# User Guide Module

> :mag_right: If you are looking for the online user guide, it has
> [moved](https://obc-guide.deepsymmetry.org/) off of
> GitHub to become easier to read and navigate.

Open Beat Control uses [Antora](https://antora.org) to build its User
Guide. This folder hosts the documentation module and playbook used to
build it. `netlify.yml` is used to build the [online
version](https://obc-guide.deepsymmetry.org/) that is managed by
[netlify](https://www.netlify.com).

The online version, which supports multiple released versions of Open
Beat Control, is built automatically by netlify whenever changes are
pushed to the relevant branches on GitHub. The netlify build command
is:

    npx antora --fetch doc/netlify.yml

And the publish directory is `doc/build/site`.

To build it locally to test changes before you push them (or open a
merge request), install Antora and its dependencies and run it with
the local playbook, by issuing these commands in the top-level project
directory:

    npm install

    npx antora --fetch doc/local.yml

Once built, you can browse the
local documentation by opening `doc/build/site/index.html`.
