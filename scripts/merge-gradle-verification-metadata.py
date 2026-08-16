#!/usr/bin/env python3
from collections import defaultdict
from html import escape
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


base_path = Path(sys.argv[1])
generated_path = Path(sys.argv[2])
base_raw = base_path.read_text(encoding="utf-8")
generated_raw = generated_path.read_text(encoding="utf-8")
base_root = ET.fromstring(base_raw)
generated_root = ET.fromstring(generated_raw)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def components(root):
    return [element for element in root.iter() if local_name(element.tag) == "component"]


def component_key(component):
    return (
        component.attrib["group"],
        component.attrib["name"],
        component.attrib["version"],
    )


def artifacts(component):
    return {
        element.attrib["name"]: element
        for element in component
        if local_name(element.tag) == "artifact"
    }


def hash_values(artifact):
    return {
        (local_name(element.tag), tuple(sorted(element.attrib.items())))
        for element in artifact
    }


def render_artifact(artifact, indent="         "):
    lines = [f'{indent}<artifact name="{escape(artifact.attrib["name"])}">']
    for child in artifact:
        attributes = " ".join(
            f'{key}="{escape(value)}"' for key, value in child.attrib.items()
        )
        lines.append(f"{indent}   <{local_name(child.tag)} {attributes}/>")
    lines.append(f"{indent}</artifact>")
    return "\n".join(lines)


def render_component(component):
    lines = [
        "      <component "
        f'group="{escape(component.attrib["group"])}" '
        f'name="{escape(component.attrib["name"])}" '
        f'version="{escape(component.attrib["version"])}">'
    ]
    for artifact in sorted(artifacts(component).values(), key=lambda item: item.attrib["name"]):
        lines.append(render_artifact(artifact))
    lines.append("      </component>")
    return "\n".join(lines)


base_components = {component_key(component): component for component in components(base_root)}
generated_components = {component_key(component): component for component in components(generated_root)}
new_components = []
artifact_additions = defaultdict(list)

for key, generated_component in generated_components.items():
    if key not in base_components:
        new_components.append(generated_component)
        continue
    existing_artifacts = artifacts(base_components[key])
    for name, generated_artifact in artifacts(generated_component).items():
        existing_artifact = existing_artifacts.get(name)
        if existing_artifact is None:
            artifact_additions[key].append(generated_artifact)
        elif hash_values(existing_artifact) != hash_values(generated_artifact):
            raise SystemExit(
                "Refusing to replace an existing verification hash for "
                f"{key[0]}:{key[1]}:{key[2]}:{name}"
            )

text = base_raw.replace("\r\n", "\n")
for key, additions in artifact_additions.items():
    group, name, version = map(re.escape, key)
    pattern = re.compile(
        rf'(?ms)^(?P<indent>[ \t]*)<component group="{group}" '
        rf'name="{name}" version="{version}">.*?^(?P=indent)</component>'
    )
    match = pattern.search(text)
    if match is None:
        raise SystemExit(f"Could not locate component {key} in verification metadata")
    block = match.group(0)
    closing = f'{match.group("indent")}</component>'
    insert_at = block.rfind(closing)
    rendered = "\n".join(render_artifact(artifact) for artifact in additions)
    replacement = block[:insert_at] + rendered + "\n" + block[insert_at:]
    text = text[: match.start()] + replacement + text[match.end() :]

if new_components:
    marker = "\n   </components>"
    insert_at = text.rfind(marker)
    if insert_at < 0:
        raise SystemExit("Could not locate the components closing tag")
    rendered = "\n" + "\n".join(
        render_component(component)
        for component in sorted(new_components, key=component_key)
    )
    text = text[:insert_at] + rendered + text[insert_at:]

ET.fromstring(text)
newline = "\r\n" if "\r\n" in base_raw else "\n"
base_path.write_text(text.replace("\n", newline), encoding="utf-8", newline="")
