package io.researchrunner.report;

import java.util.UUID;

/** Prepared bytes: no database writes until final report publication commits. */
public record ReportFigure(UUID id,String filename,byte[] png) {
    public ReportFigure {png=png.clone();}
    @Override public byte[] png(){return png.clone();}
}
