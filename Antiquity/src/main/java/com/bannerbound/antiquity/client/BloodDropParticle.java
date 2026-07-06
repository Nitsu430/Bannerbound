package com.bannerbound.antiquity.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A red blood droplet: it's flung out, falls under gravity, and vanishes the instant it hits the
 * ground - a little burst of blood from a wounded animal. Used by the bleed pulses and spear hits.
 * Tuning is deliberate: gravity 0.4 (the vanilla-ish 0.06 just hangs in the air), doubled quad size
 * for visibility, a slight upward burst (+0.08 yd) so drops arc, and physics on so onGround removes
 * the particle the moment it lands.
 */
@OnlyIn(Dist.CLIENT)
public class BloodDropParticle extends TextureSheetParticle {
    protected BloodDropParticle(ClientLevel level, double x, double y, double z,
                                double dx, double dy, double dz) {
        super(level, x, y, z);
        this.gravity = 0.4F;
        this.setColor(0.45F, 0.0F, 0.0F);
        this.quadSize *= 2.0F;
        this.lifetime = 60;
        this.hasPhysics = true;
        this.xd = dx;
        this.yd = dy + 0.08;
        this.zd = dz;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.onGround) {
            this.remove();
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z, double dx, double dy, double dz) {
            BloodDropParticle particle = new BloodDropParticle(level, x, y, z, dx, dy, dz);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
