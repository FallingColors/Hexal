from importlib.resources import Package

from hexdoc.plugin import (
    HookReturn,
    LoadTaggedUnionsImpl,
    ModPlugin,
    ModPluginImpl,
    ModPluginWithBook,
    hookimpl,
)
from typing_extensions import override

import hexdoc_hexal
from hexdoc_hexal import pages

from .__gradle_version__ import FULL_VERSION, GRADLE_VERSION
from .__version__ import PY_VERSION


class HexalPlugin(ModPluginImpl, LoadTaggedUnionsImpl):
    @staticmethod
    @hookimpl
    def hexdoc_mod_plugin(branch: str) -> ModPlugin:
        return HexalModPlugin(branch=branch)

    @staticmethod
    @hookimpl
    def hexdoc_load_tagged_unions() -> HookReturn[Package]:
        return pages


class HexalModPlugin(ModPluginWithBook):
    @property
    @override
    def modid(self) -> str:
        return "hexal"

    @property
    @override
    def full_version(self) -> str:
        return FULL_VERSION

    @property
    @override
    def mod_version(self) -> str:
        return GRADLE_VERSION

    @property
    @override
    def plugin_version(self) -> str:
        return PY_VERSION

    @override
    def resource_dirs(self) -> HookReturn[Package]:
        # lazy import because generated may not exist when this file is loaded
        # eg. when generating the contents of generated
        # so we only want to import it if we actually need it
        from ._export import generated

        return generated

    @override
    def jinja_template_root(self) -> tuple[Package, str]:
        return hexdoc_hexal, "_templates"
