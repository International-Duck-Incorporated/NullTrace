package net.nulltrace.mod.mixin.client;

//? if >=1.20.5 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.protection.LangOnlyPackResources;
import net.nulltrace.mod.protection.PackStripHandler;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;

// 1.20.5+ multi-pack era. Wraps each server pack's supplier with LangOnlyPackResources
// when PackStripHandler.isWrapped(uuid) says so. Lang stays applied even when stripped.
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {

    @WrapOperation(
        method = "loadRequestedPacks",
        at = @At(value = "NEW", target = "(Ljava/nio/file/Path;)Lnet/minecraft/server/packs/FilePackResources$FileResourcesSupplier;"))
    private FilePackResources.FileResourcesSupplier nulltrace$wrapFilePackSupplier(
            Path file,
            Operation<FilePackResources.FileResourcesSupplier> original,
            @Local PackReloadConfig.IdAndPath idAndPath) {

        FilePackResources.FileResourcesSupplier real = original.call(file);

        if (!NullTraceConfig.getInstance().shouldWrapServerPacks()) return real;

        final java.util.UUID packId = idAndPath.id();
        if (!PackStripHandler.isWrapped(packId)) return real;

        //? if >=26.3 {
        /*return new FilePackResources.FileResourcesSupplier(file) {
            @Override
            public net.minecraft.server.packs.PackMetadataResources openMetadata(PackLocationInfo loc) {
                return real.openMetadata(loc);
            }

            @Override
            public java.util.stream.Stream<PackResources> openResources(PackLocationInfo loc, Pack.Metadata md) {
                return real.openResources(loc, md).map(res -> new LangOnlyPackResources(res, packId));
            }
        };
        *///?} else {
        return new FilePackResources.FileResourcesSupplier(file) {
            @Override
            public PackResources openPrimary(PackLocationInfo loc) {
                return new LangOnlyPackResources(real.openPrimary(loc), packId);
            }

            @Override
            public PackResources openFull(PackLocationInfo loc, Pack.Metadata md) {
                return new LangOnlyPackResources(real.openFull(loc, md), packId);
            }
        };
        //?}
    }
}
//?} elif >=1.20.3 {
/*
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.protection.LangOnlyPackResources;
import net.nulltrace.mod.protection.PackStripHandler;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;

// 1.20.3-1.20.4: same wrap as >=1.20.5 but with the older 2-arg supplier ctor
// and String-keyed openPrimary/openFull (pre-PackLocationInfo).
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {

    @WrapOperation(
        method = "loadRequestedPacks",
        at = @At(value = "NEW", target = "(Ljava/nio/file/Path;Z)Lnet/minecraft/server/packs/FilePackResources$FileResourcesSupplier;"))
    private FilePackResources.FileResourcesSupplier nulltrace$wrapFilePackSupplier(
            Path file,
            boolean isBuiltin,
            Operation<FilePackResources.FileResourcesSupplier> original,
            @Local PackReloadConfig.IdAndPath idAndPath) {

        FilePackResources.FileResourcesSupplier real = original.call(file, isBuiltin);

        if (!NullTraceConfig.getInstance().shouldWrapServerPacks()) return real;

        final java.util.UUID packId = idAndPath.id();
        if (!PackStripHandler.isWrapped(packId)) return real;

        return new FilePackResources.FileResourcesSupplier(file, isBuiltin) {
            @Override
            public PackResources openPrimary(String id) {
                return new LangOnlyPackResources(real.openPrimary(id), packId);
            }

            @Override
            public PackResources openFull(String id, Pack.Info info) {
                return new LangOnlyPackResources(real.openFull(id, info), packId);
            }
        };
    }
}
*///?} else {
/*
import net.minecraft.network.protocol.PacketUtils;
import org.spongepowered.asm.mixin.Mixin;

// 1.20.1/1.20.2: DownloadedPackSource is at client.resources (no multi-pack). Strip
// lives in LegacyDownloadedPackSourceMixin instead; this entry stays as a stub.
@Mixin(PacketUtils.class)
public abstract class DownloadedPackSourceMixin {
}
*///?}
