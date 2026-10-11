package dev.pocortx.mixin;

import dev.pocortx.rt.RtMaterials;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin implements RtMaterials.AtlasSpriteAccess {
    @Shadow
    private Map<Identifier, TextureAtlasSprite> texturesByName;

    @Override
    public Iterable<TextureAtlasSprite> pocortx$sprites() {
        return texturesByName.values();
    }
}
