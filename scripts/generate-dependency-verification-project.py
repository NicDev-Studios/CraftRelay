#!/usr/bin/env python3
from pathlib import Path
import sys
import tomllib


output = Path(sys.argv[1])
catalog_path = Path("gradle/libs.versions.toml")
catalog = tomllib.loads(catalog_path.read_text(encoding="utf-8"))
versions = catalog["versions"]

repositories = """repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
"""

plugin_lines = []
for plugin in catalog["plugins"].values():
    version = versions[plugin["version"]["ref"]]
    plugin_lines.append(
        f'    id("{plugin["id"]}") version "{version}" apply false'
    )

dependency_lines = []
for library in catalog["libraries"].values():
    module = library["module"]
    group, _, _ = module.partition(":")
    if group in {"io.papermc.paper", "com.velocitypowered"}:
        continue
    if "version" in library:
        version = library["version"]["ref"]
        version = versions[version]
    elif group == "org.junit.jupiter" or group == "org.junit.platform":
        version = versions["junit"]
    else:
        continue
    dependency_lines.append(f'    add("verification", "{module}:{version}")')

output.mkdir(parents=True, exist_ok=True)
(output / "settings.gradle.kts").write_text(
    """pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "dependency-verification-seed"
""",
    encoding="utf-8",
)
(output / "build.gradle.kts").write_text(
    "plugins {\n"
    + "\n".join(plugin_lines)
    + "\n}\n\n"
    + repositories
    + "\nconfigurations {\n    create(\"verification\")\n}\n\n"
    + "dependencies {\n"
    + "\n".join(dependency_lines)
    + "\n}\n\n"
    + "tasks.register(\"resolveVerificationDependencies\") {\n"
    + "    doLast { configurations[\"verification\"].resolve() }\n"
    + "}\n",
    encoding="utf-8",
)
