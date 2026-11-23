package com.specshield.ledgerline.web;

import com.specshield.ledgerline.demo.DemoProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Meta", description = "About this deployment")
public class MetaController {

    @Schema(name = "Meta", description = "Banner data for UIs")
    public record MetaResponse(
            @Schema(description = "Always true: synthetic data only")
            boolean demo,

            @Schema(example = "03:00 UTC") String dataResetsAt,
            @Schema(example = "0.1.0-SNAPSHOT") String version,

            @Schema(description = "Commit of this build, or 'unknown'")
            String gitSha) {}

    private final MetaResponse meta;

    public MetaController(DemoProperties demo, ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        this.meta = new MetaResponse(
                true, demo.dataResetsAt(), info == null ? "unknown" : info.getVersion(), demo.gitSha());
    }

    @GetMapping("/api/v1/meta")
    @Operation(summary = "Deployment banner: demo flag, data reset time, version")
    MetaResponse meta() {
        return meta;
    }
}
